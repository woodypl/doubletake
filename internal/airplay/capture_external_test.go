package airplay

import (
	"bytes"
	"io"
	"testing"
	"time"
)

func TestExternalVideoFeedWaitsForKeyframe(t *testing.T) {
	capture, feed := NewExternalVideoCapture(nil)
	base := time.Now()
	if err := feed.Push([]byte{0, 0, 0, 1, 0x41, 1}, false, base); err != nil {
		t.Fatal(err)
	}
	if err := feed.Push([]byte{0, 0, 0, 1, 0x65, 2}, true, base.Add(time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if err := feed.Push([]byte{0, 0, 0, 1, 0x41, 3}, false, base.Add(2*time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	unit, err := capture.ReadVideoAccessUnit()
	if err != nil {
		t.Fatal(err)
	}
	if unit.AnnexB[5] != 2 || !unit.PTS.Equal(base.Add(time.Millisecond)) {
		t.Fatalf("first unit = %v at %v, want keyframe", unit.AnnexB, unit.PTS)
	}
	unit, err = capture.ReadVideoAccessUnit()
	if err != nil || unit.AnnexB[5] != 3 {
		t.Fatalf("second unit = %v, %v", unit.AnnexB, err)
	}
	if feed.Dropped() != 1 {
		t.Fatalf("dropped = %d, want 1", feed.Dropped())
	}
	capture.Stop()
	if _, err := capture.ReadVideoAccessUnit(); err != io.EOF {
		t.Fatalf("read after stop = %v, want EOF", err)
	}
	if err := feed.Push([]byte{1}, true, base); err != ErrExternalFeedClosed {
		t.Fatalf("push after close = %v", err)
	}
}

func TestExternalVideoFeedOverflowRequestsKeyframe(t *testing.T) {
	requests := 0
	capture, feed := NewExternalVideoCapture(func() { requests++ })
	now := time.Now()
	feed.Push([]byte{0, 0, 0, 1, 0x65}, true, now)
	for i := 0; i < defaultExternalVideoQueue; i++ {
		feed.Push([]byte{0, 0, 0, 1, 0x41, byte(i)}, false, now)
	}
	if requests != 1 {
		t.Fatalf("keyframe requests = %d, want 1", requests)
	}
	// Non-keyframes are now discarded until a keyframe arrives.
	feed.Push([]byte{0, 0, 0, 1, 0x41, 0xee}, false, now)
	feed.Push([]byte{0, 0, 0, 1, 0x65, 0xff}, true, now)
	unit, err := capture.ReadVideoAccessUnit()
	if err != nil || unit.AnnexB[5] != 0xff {
		t.Fatalf("after overflow got %v, %v; want the new keyframe", unit.AnnexB, err)
	}
}

func pcmFrames(samples int, value byte) []byte {
	return bytes.Repeat([]byte{value, 0, value, 0}, samples)
}

func TestExternalAudioFeedSlicesFramesWithContinuousTiming(t *testing.T) {
	capture, feed := NewExternalAudioCapture(AudioCodecALAC)
	_, spf, _, _, _, _ := AudioCodecALAC.Info()
	base := time.Now()
	// 1000-sample chunks do not align with 352-sample codec frames.
	for i := 0; i < 4; i++ {
		pts := base.Add(audioSamplesDuration(uint64(i * 1000)))
		if err := feed.Push(pcmFrames(1000, byte(i+1)), pts); err != nil {
			t.Fatal(err)
		}
	}
	frame := make([]byte, int(spf)*audioBytesPerSampleFrame)
	var previous audioPCMFramePosition
	for i := 0; i < 10; i++ {
		position, err := feed.ReadPCMFramePosition(frame)
		if err != nil {
			t.Fatal(err)
		}
		wantPTS := base.Add(audioSamplesDuration(uint64(i) * uint64(spf)))
		if d := position.PTS.Sub(wantPTS); d > time.Microsecond || d < -time.Microsecond {
			t.Fatalf("frame %d PTS off by %v", i, d)
		}
		if i > 0 && position.SourceRTP-previous.SourceRTP != uint32(spf) {
			t.Fatalf("frame %d RTP advanced by %d", i, position.SourceRTP-previous.SourceRTP)
		}
		previous = position
	}
	// ReadFrameAt goes through the ALAC encoder end to end.
	out := make([]byte, 8192)
	if n, _, err := capture.ReadFrameAt(out); err != nil || n == 0 {
		t.Fatalf("ReadFrameAt = %d, %v", n, err)
	}
	capture.Stop()
}

func TestExternalAudioFeedRestartsOnDiscontinuity(t *testing.T) {
	_, feed := NewExternalAudioCapture(AudioCodecALAC)
	_, spf, _, _, _, _ := AudioCodecALAC.Info()
	base := time.Now()
	feed.Push(pcmFrames(500, 1), base)
	later := base.Add(time.Second)
	feed.Push(pcmFrames(500, 2), later)
	frame := make([]byte, int(spf)*audioBytesPerSampleFrame)
	first, err := feed.ReadPCMFramePosition(frame)
	if err != nil || !first.PTS.Equal(base) {
		t.Fatalf("first = %v, %v", first.PTS, err)
	}
	second, err := feed.ReadPCMFramePosition(frame)
	if err != nil {
		t.Fatal(err)
	}
	if !second.PTS.Equal(later) || frame[0] != 2 {
		t.Fatalf("after gap PTS=%v sample=%d, want restart at %v", second.PTS, frame[0], later)
	}
	feed.Close()
	if _, err := feed.ReadPCMFramePosition(frame); err != io.EOF {
		t.Fatalf("read after close = %v", err)
	}
}
