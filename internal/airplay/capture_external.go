package airplay

import (
	"errors"
	"fmt"
	"io"
	"sync"
	"time"
)

// External capture feeds let a host application (for example an Android app
// using MediaProjection and MediaCodec) supply already-encoded video and raw
// PCM audio instead of a gst-launch-1.0 pipeline. They plug into the same
// ScreenCapture/AudioCapture types the GStreamer paths produce, so the AirPlay
// session code is unchanged.

// ErrExternalFeedClosed is returned by pushes after a feed has been closed.
var ErrExternalFeedClosed = errors.New("external capture feed closed")

const (
	defaultExternalVideoQueue = 12
	// Two seconds of 352-sample frames is ample slack for a phone's capture
	// thread; anything older is stale for a live mirror anyway.
	defaultExternalAudioQueue = 256
	// A gap or jump in pushed audio timestamps larger than this restarts the
	// PCM timeline instead of silently compressing or stretching time.
	externalAudioDiscontinuity = 60 * time.Millisecond
)

// ExternalVideoFeed is the producer side of an externally encoded H.264
// stream. Push complete access units in Annex-B form. Keyframes must carry
// their SPS and PPS (prepend the encoder's codec-config data).
type ExternalVideoFeed struct {
	mu             sync.Mutex
	cond           *sync.Cond
	queue          []VideoAccessUnit
	limit          int
	closed         bool
	closeErr       error
	waitCh         chan struct{}
	needKeyframe   bool
	dropped        uint64
	onNeedKeyframe func()
}

// NewExternalVideoCapture returns a ScreenCapture whose frames come from the
// returned feed. onNeedKeyframe, if non-nil, is called (without locks held)
// when the feed discarded frames and needs a new keyframe to resume.
func NewExternalVideoCapture(onNeedKeyframe func()) (*ScreenCapture, *ExternalVideoFeed) {
	feed := &ExternalVideoFeed{
		limit:          defaultExternalVideoQueue,
		waitCh:         make(chan struct{}),
		onNeedKeyframe: onNeedKeyframe,
		// Nothing decodes before the first keyframe.
		needKeyframe: true,
	}
	feed.cond = sync.NewCond(&feed.mu)
	capture := &ScreenCapture{
		stdout:     externalFeedCloser{close: func() error { feed.Close(nil); return nil }},
		frames:     feed,
		waitCh:     feed.waitCh,
		streamOnly: true,
	}
	return capture, feed
}

// Push queues one access unit. pts is the capture time of the picture in the
// local clock. A full queue is never resolved by dropping single frames, which
// would corrupt the reference chain: the whole backlog is discarded and the
// feed waits for the next keyframe.
func (f *ExternalVideoFeed) Push(annexB []byte, keyframe bool, pts time.Time) error {
	if len(annexB) == 0 {
		return nil
	}
	data := append([]byte(nil), annexB...)
	requestKeyframe := false
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return ErrExternalFeedClosed
	}
	switch {
	case f.needKeyframe && !keyframe:
		f.dropped++
		f.mu.Unlock()
		return nil
	case len(f.queue) >= f.limit:
		f.dropped += uint64(len(f.queue)) + 1
		f.queue = f.queue[:0]
		if keyframe {
			f.queue = append(f.queue, VideoAccessUnit{AnnexB: data, PTS: pts})
			f.needKeyframe = false
		} else {
			f.needKeyframe = true
			requestKeyframe = true
		}
		dbg("[EXTERNAL] video queue overflow; dropped backlog (total dropped %d)", f.dropped)
	default:
		f.queue = append(f.queue, VideoAccessUnit{AnnexB: data, PTS: pts})
		if keyframe {
			f.needKeyframe = false
		}
	}
	f.cond.Signal()
	f.mu.Unlock()
	if requestKeyframe && f.onNeedKeyframe != nil {
		f.onNeedKeyframe()
	}
	return nil
}

// ReadVideoAccessUnit implements videoAccessUnitReader.
func (f *ExternalVideoFeed) ReadVideoAccessUnit() (VideoAccessUnit, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for len(f.queue) == 0 && !f.closed {
		f.cond.Wait()
	}
	if len(f.queue) == 0 {
		if f.closeErr != nil {
			return VideoAccessUnit{}, f.closeErr
		}
		return VideoAccessUnit{}, io.EOF
	}
	unit := f.queue[0]
	f.queue[0] = VideoAccessUnit{}
	f.queue = f.queue[1:]
	return unit, nil
}

// Close ends the stream. A nil error is reported to the reader as EOF.
func (f *ExternalVideoFeed) Close(err error) {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return
	}
	f.closed = true
	f.closeErr = err
	f.queue = nil
	close(f.waitCh)
	f.cond.Broadcast()
	f.mu.Unlock()
}

// Dropped reports how many access units were discarded.
func (f *ExternalVideoFeed) Dropped() uint64 {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.dropped
}

type externalFeedCloser struct {
	close func() error
}

func (c externalFeedCloser) Read([]byte) (int, error) { return 0, io.EOF }
func (c externalFeedCloser) Close() error             { return c.close() }

// ExternalAudioFeed is the producer side of a raw PCM stream: interleaved
// stereo signed 16-bit little-endian samples at 44.1 kHz.
type ExternalAudioFeed struct {
	mu       sync.Mutex
	cond     *sync.Cond
	chunks   []externalPCMChunk
	queued   int // bytes queued across chunks
	limit    int // byte limit
	closed   bool
	waitCh   chan struct{}
	dropped  uint64
	timeline externalPCMTimeline
}

type externalPCMChunk struct {
	pcm []byte
	pts time.Time
}

// externalPCMTimeline keeps a continuous sample counter (the source RTP
// domain) and correlates it with pushed presentation times, like the
// GStreamer RTP/L16 reader does.
type externalPCMTimeline struct {
	have            bool
	pcm             []byte
	timelinePTS     time.Time // PTS of sample index timelineRTP
	timelineRTP     uint32
	receivedSamples uint64
	consumedSamples uint64
	discontinuities uint64
}

// NewExternalAudioCapture returns an AudioCapture for codec whose PCM comes
// from the returned feed.
func NewExternalAudioCapture(codec AudioCodec) (*AudioCapture, *ExternalAudioFeed) {
	_, spf, _, _, _, _ := codec.Info()
	feed := &ExternalAudioFeed{
		limit:  defaultExternalAudioQueue * int(spf) * audioBytesPerSampleFrame,
		waitCh: make(chan struct{}),
	}
	feed.cond = sync.NewCond(&feed.mu)
	capture := &AudioCapture{
		pcmPipe:   externalFeedCloser{close: func() error { feed.Close(); return nil }},
		pcmFrames: feed,
		waitCh:    feed.waitCh,
		codec:     codec,
	}
	return capture, feed
}

// Push queues PCM whose first sample was captured at pts.
func (f *ExternalAudioFeed) Push(pcm []byte, pts time.Time) error {
	if len(pcm) == 0 {
		return nil
	}
	if len(pcm)%audioBytesPerSampleFrame != 0 {
		return fmt.Errorf("external audio: %d bytes is not whole stereo S16 samples", len(pcm))
	}
	data := append([]byte(nil), pcm...)
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return ErrExternalFeedClosed
	}
	f.chunks = append(f.chunks, externalPCMChunk{pcm: data, pts: pts})
	f.queued += len(data)
	for f.queued > f.limit && len(f.chunks) > 1 {
		// Drop the oldest whole chunk; the reader sees a timestamp jump and
		// restarts its timeline cleanly.
		f.queued -= len(f.chunks[0].pcm)
		f.dropped += uint64(len(f.chunks[0].pcm) / audioBytesPerSampleFrame)
		f.chunks[0] = externalPCMChunk{}
		f.chunks = f.chunks[1:]
	}
	f.cond.Signal()
	return nil
}

func (f *ExternalAudioFeed) nextChunkLocked() (externalPCMChunk, bool) {
	for len(f.chunks) == 0 && !f.closed {
		f.cond.Wait()
	}
	if len(f.chunks) == 0 {
		return externalPCMChunk{}, false
	}
	chunk := f.chunks[0]
	f.chunks[0] = externalPCMChunk{}
	f.chunks = f.chunks[1:]
	f.queued -= len(chunk.pcm)
	return chunk, true
}

// ReadPCMFrame implements audioPCMFrameReader.
func (f *ExternalAudioFeed) ReadPCMFrame(dst []byte) (time.Time, error) {
	position, err := f.ReadPCMFramePosition(dst)
	return position.PTS, err
}

// ReadPCMFramePosition implements audioPCMFramePositionReader.
func (f *ExternalAudioFeed) ReadPCMFramePosition(dst []byte) (audioPCMFramePosition, error) {
	if len(dst) == 0 || len(dst)%audioBytesPerSampleFrame != 0 {
		return audioPCMFramePosition{}, fmt.Errorf("external audio: frame size %d is not whole stereo S16 samples", len(dst))
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	t := &f.timeline
	for len(t.pcm) < len(dst) {
		chunk, ok := f.nextChunkLocked()
		if !ok {
			return audioPCMFramePosition{}, io.EOF
		}
		t.accept(chunk)
	}
	sourceRTP := t.timelineRTP + uint32(t.consumedSamples)
	pts := t.timelinePTS.Add(audioSamplesDuration(t.consumedSamples))
	copy(dst, t.pcm[:len(dst)])
	t.pcm = t.pcm[len(dst):]
	t.consumedSamples += uint64(len(dst) / audioBytesPerSampleFrame)
	return audioPCMFramePosition{PTS: pts, SourceRTP: sourceRTP, HasSourceRTP: true}, nil
}

func (t *externalPCMTimeline) accept(chunk externalPCMChunk) {
	samples := uint64(len(chunk.pcm) / audioBytesPerSampleFrame)
	if t.have {
		expected := t.timelinePTS.Add(audioSamplesDuration(t.receivedSamples))
		delta := chunk.pts.Sub(expected)
		if delta > externalAudioDiscontinuity || delta < -externalAudioDiscontinuity {
			t.discontinuities++
			dbg("[EXTERNAL] audio timeline discontinuity (%v); restarting with %d partial bytes dropped", delta, len(t.pcm))
			// Keep the RTP domain moving forward across the restart.
			t.timelineRTP += uint32(t.receivedSamples)
			t.pcm = t.pcm[:0]
			t.timelinePTS = chunk.pts
			t.receivedSamples = 0
			t.consumedSamples = 0
		}
		// Follow the newest PTS correlation, as the GStreamer reader does, so
		// slow drift between the sample clock and the system clock never
		// accumulates into a discontinuity.
		t.timelinePTS = chunk.pts.Add(-audioSamplesDuration(t.receivedSamples))
	} else {
		t.have = true
		t.timelinePTS = chunk.pts
	}
	t.pcm = append(t.pcm, chunk.pcm...)
	t.receivedSamples += samples
}

// Close ends the stream; the reader drains nothing further and sees EOF.
func (f *ExternalAudioFeed) Close() {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return
	}
	f.closed = true
	f.chunks = nil
	f.queued = 0
	close(f.waitCh)
	f.cond.Broadcast()
}

// Dropped reports how many sample frames were discarded for lack of space.
func (f *ExternalAudioFeed) Dropped() uint64 {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.dropped
}
