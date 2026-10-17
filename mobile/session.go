// Package mobile exposes a doubletake mirroring session to Android through
// gomobile bind. The host app captures and encodes the screen (MediaCodec
// H.264) and captures playback audio (16-bit stereo PCM at 44.1 kHz); this
// package does discovery-free connection, pairing and the AirPlay session.
//
// Only gomobile-compatible types appear in the exported API.
package mobile

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"doubletake/internal/airplay"
)

// Session states reported through Listener.OnState.
const (
	StateConnecting = "connecting"
	StatePairing    = "pairing"
	StateNeedCode   = "need_code"
	StateSettingUp  = "setting_up"
	StateStreaming  = "streaming"
	StateStopped    = "stopped"
	StateFailed     = "failed"
)

// Code kinds passed with StateNeedCode.
const (
	CodePIN      = "pin"
	CodePassword = "password"
)

// Listener receives session callbacks. Methods may be called from any thread.
type Listener interface {
	// OnState reports a state change. For StateNeedCode, detail is CodePIN or
	// CodePassword; for StateFailed it is the error text.
	OnState(state string, detail string)
	// OnVideoFormat asks the host to start its H.264 encoder at this size and
	// push access units with PushVideo.
	OnVideoFormat(width int, height int, fps int)
	// OnKeyframeNeeded asks the encoder for an IDR as soon as possible.
	OnKeyframeNeeded()
	// OnAudioFormat asks the host to start pushing PCM with PushAudio.
	OnAudioFormat(sampleRate int, channels int)
}

// Config holds session options. Create it with NewConfig.
type Config struct {
	Host            string
	Port            int
	CredentialsPath string
	FPS             int
	BitrateKbps     int
	TargetLatencyMs int
	NoAudio         bool
	Debug           bool
	// Password is a receiver's fixed password ("Require Password"), if known.
	Password string
	// ForcePair discards saved credentials and pairs again.
	ForcePair bool
}

// NewConfig returns a Config with defaults for host.
func NewConfig(host string) *Config {
	return &Config{Host: host, Port: 7000, FPS: 30}
}

// Session is one mirroring session. It is single-use.
type Session struct {
	cfg      Config
	listener Listener

	ctx    context.Context
	cancel context.CancelFunc

	mu       sync.Mutex
	started  bool
	video    *airplay.ExternalVideoFeed
	audio    *airplay.ExternalAudioFeed
	codeCh   chan string
	done     chan struct{}
	stopOnce sync.Once
}

// NewSession creates a session; nothing happens until Start.
func NewSession(cfg *Config, listener Listener) *Session {
	ctx, cancel := context.WithCancel(context.Background())
	return &Session{
		cfg:      *cfg,
		listener: listener,
		ctx:      ctx,
		cancel:   cancel,
		codeCh:   make(chan string, 1),
		done:     make(chan struct{}),
	}
}

// Start runs the session in the background.
func (s *Session) Start() {
	s.mu.Lock()
	if s.started {
		s.mu.Unlock()
		return
	}
	s.started = true
	s.mu.Unlock()
	go func() {
		defer close(s.done)
		err := s.run()
		s.closeFeeds()
		switch {
		case err == nil || errors.Is(err, context.Canceled) || s.ctx.Err() != nil:
			s.listener.OnState(StateStopped, "")
		default:
			log.Printf("session failed: %v", err)
			s.listener.OnState(StateFailed, err.Error())
		}
	}()
}

// SubmitCode answers StateNeedCode with the PIN or password the user entered.
// An empty string cancels the session.
func (s *Session) SubmitCode(code string) {
	select {
	case s.codeCh <- code:
	default:
	}
}

// PushVideo queues one encoded H.264 access unit (Annex-B). ageNanos is how
// long ago the frame was captured, measured on the host's monotonic clock
// (System.nanoTime() - presentationTimeUs*1000). Keyframes must include SPS
// and PPS.
func (s *Session) PushVideo(data []byte, keyframe bool, ageNanos int64) error {
	s.mu.Lock()
	feed := s.video
	s.mu.Unlock()
	if feed == nil {
		return nil
	}
	return feed.Push(data, keyframe, ptsFromAge(ageNanos))
}

// PushAudio queues interleaved stereo S16LE PCM. ageNanos is how long ago its
// first sample was captured.
func (s *Session) PushAudio(pcm []byte, ageNanos int64) error {
	s.mu.Lock()
	feed := s.audio
	s.mu.Unlock()
	if feed == nil {
		return nil
	}
	return feed.Push(pcm, ptsFromAge(ageNanos))
}

// Stop ends the session and waits briefly for it to wind down.
func (s *Session) Stop() {
	s.stopOnce.Do(func() {
		s.cancel()
		s.SubmitCode("")
		s.closeFeeds()
	})
	select {
	case <-s.done:
	case <-time.After(3 * time.Second):
	}
}

func ptsFromAge(ageNanos int64) time.Time {
	if ageNanos < 0 {
		ageNanos = 0
	}
	return time.Now().Add(-time.Duration(ageNanos))
}

func (s *Session) closeFeeds() {
	s.mu.Lock()
	video, audio := s.video, s.audio
	s.mu.Unlock()
	if video != nil {
		video.Close(nil)
	}
	if audio != nil {
		audio.Close()
	}
}

func (s *Session) waitForCode(kind string) (string, error) {
	s.listener.OnState(StateNeedCode, kind)
	select {
	case <-s.ctx.Done():
		return "", s.ctx.Err()
	case code := <-s.codeCh:
		if code == "" {
			return "", context.Canceled
		}
		return code, nil
	}
}

func (s *Session) run() error {
	cfg := s.cfg
	if cfg.Port == 0 {
		cfg.Port = 7000
	}
	if cfg.FPS <= 0 {
		cfg.FPS = 30
	}
	ctx := s.ctx
	airplay.SetDebugMode(cfg.Debug)
	airplay.SetAssumeWirelessConnection(true)
	airplay.SetTargetLatency(time.Duration(cfg.TargetLatencyMs) * time.Millisecond)

	s.listener.OnState(StateConnecting, "")
	credential := cfg.Password
	newClient := func() *airplay.AirPlayClient {
		client := airplay.NewAirPlayClient(cfg.Host, cfg.Port)
		client.SetPassword(credential)
		return client
	}
	client := newClient()
	if err := client.Connect(ctx); err != nil {
		return fmt.Errorf("connect to %s:%d: %w", cfg.Host, cfg.Port, err)
	}
	defer func() { _ = client.Close() }()

	info, err := client.GetInfo()
	if err != nil {
		return fmt.Errorf("get receiver info: %w", err)
	}
	log.Printf("connected to: %s (model: %s)", info.Name, info.Model)
	if credential == "" && info.RequiresPassword() {
		if credential, err = s.waitForCode(CodePassword); err != nil {
			return err
		}
		client.SetPassword(credential)
	}

	store, err := airplay.NewCredentialStore(cfg.CredentialsPath)
	if err != nil {
		return fmt.Errorf("open credentials: %w", err)
	}
	reconnect := func() error {
		_ = client.Close()
		client = newClient()
		if err := client.Connect(ctx); err != nil {
			return fmt.Errorf("reconnect: %w", err)
		}
		info, err = client.GetInfo()
		if err != nil {
			return fmt.Errorf("get info after reconnect: %w", err)
		}
		return nil
	}
	pairWithCode := func(expectPIN bool) error {
		code := credential
		if code == "" || expectPIN {
			if displayErr := client.StartPINDisplay(); displayErr != nil {
				log.Printf("warning: failed to trigger PIN display: %v", displayErr)
				expectPIN = false
			}
			kind := CodePassword
			if expectPIN {
				kind = CodePIN
			}
			if code, err = s.waitForCode(kind); err != nil {
				return err
			}
		}
		credential = code
		client.SetPassword(code)
		s.listener.OnState(StatePairing, "")
		if err := client.Pair(ctx, code); err != nil {
			return fmt.Errorf("pairing failed: %w", err)
		}
		if client.PairKeys != nil {
			if err := store.SavePairing(info.DeviceID, client.PairingID,
				client.PairKeys.Ed25519Public, client.PairKeys.Ed25519Private,
				client.PairingProtocol()); err != nil {
				log.Printf("warning: failed to save credentials: %v", err)
			} else {
				log.Printf("credentials saved")
			}
		}
		return nil
	}
	freshPair := func() error {
		requirement := info.RequiredPairingCredential()
		switch {
		case requirement == airplay.PairingCredentialPassword:
			return pairWithCode(false)
		case requirement == airplay.PairingCredentialPIN:
			return pairWithCode(true)
		}
		if err := client.Pair(ctx, ""); err != nil {
			if info.RequiresPassword() {
				return fmt.Errorf("transient pairing failed for password-protected receiver: %w", err)
			}
			log.Printf("transient pairing failed: %v, requesting pairing credentials", err)
			if err := reconnect(); err != nil {
				return err
			}
			return pairWithCode(false)
		}
		return nil
	}

	s.listener.OnState(StatePairing, "")
	var saved *airplay.SavedCredentials
	if !cfg.ForcePair {
		saved = store.Lookup(info.DeviceID)
	}
	if saved != nil && saved.HasPairingCredentials() {
		log.Printf("using saved credentials")
		verifyErr := client.RestorePairingCredentials(saved)
		if verifyErr == nil {
			verifyErr = client.PairVerify(ctx)
		}
		if verifyErr != nil {
			log.Printf("pair-verify with saved credentials failed: %v", verifyErr)
			if err := reconnect(); err != nil {
				return err
			}
			if err := freshPair(); err != nil {
				return err
			}
		}
	} else if cfg.ForcePair && !(info.RequiresPassword() && info.RequiredPairingCredential() == airplay.PairingCredentialNone) {
		if err := pairWithCode(!info.RequiresPassword()); err != nil {
			return err
		}
	} else if err := freshPair(); err != nil {
		return err
	}
	log.Println("pairing complete")

	if client.FpEkey == nil {
		if err := client.FairPlaySetup(ctx); err != nil {
			if !errors.Is(err, airplay.ErrFairPlayUnsupported) {
				return fmt.Errorf("FairPlay setup failed: %w", err)
			}
			log.Printf("FairPlay SAP unsupported (%v); continuing with pair-verify DataStream setup", err)
		} else {
			log.Println("FairPlay setup complete")
		}
	}

	s.listener.OnState(StateSettingUp, "")
	streamCfg := airplay.StreamConfig{
		FPS:        cfg.FPS,
		Bitrate:    cfg.BitrateKbps,
		VideoCodec: airplay.VideoCodecH264,
		NoAudio:    cfg.NoAudio,
	}
	var capture *airplay.ScreenCapture
	prepareVideo := func(width, height int, codec airplay.VideoCodec) (airplay.VideoPreparationResult, error) {
		if capture != nil {
			return airplay.VideoPreparationResult{}, nil
		}
		if codec != airplay.VideoCodecH264 && codec != "" {
			return airplay.VideoPreparationResult{}, fmt.Errorf("receiver selected %s; only H.264 is supported", codec)
		}
		var feed *airplay.ExternalVideoFeed
		capture, feed = airplay.NewExternalVideoCapture(s.listener.OnKeyframeNeeded)
		s.mu.Lock()
		s.video = feed
		s.mu.Unlock()
		log.Printf("receiver canvas %dx%d; starting phone encoder", width, height)
		s.listener.OnVideoFormat(width, height, cfg.FPS)
		return airplay.VideoPreparationResult{}, nil
	}

	session, err := client.SetupMirrorWithCalibratedVideoPreparation(ctx, streamCfg, prepareVideo)
	if errors.Is(err, airplay.ErrCredentialsRequired) {
		if credential, err = s.waitForCode(CodePassword); err != nil {
			return err
		}
		client.SetPassword(credential)
		session, err = client.SetupMirrorWithCalibratedVideoPreparation(ctx, streamCfg, prepareVideo)
	}
	if err != nil {
		return fmt.Errorf("mirror setup failed: %w", err)
	}
	if capture == nil {
		session.Close()
		return errors.New("mirror setup completed without preparing video")
	}
	defer session.Close()
	log.Printf("mirror session ready (data port: %d)", session.DataPort)

	go func() {
		<-ctx.Done()
		capture.Stop()
		session.Close()
	}()

	if !cfg.NoAudio && session.HasAudio() {
		if codec := session.AudioCodec(); codec != airplay.AudioCodecALAC {
			log.Printf("receiver selected audio codec %d; only ALAC is supported, continuing without audio", codec)
		} else {
			audioCapture, feed := airplay.NewExternalAudioCapture(codec)
			s.mu.Lock()
			s.audio = feed
			s.mu.Unlock()
			defer audioCapture.Stop()
			s.listener.OnAudioFormat(44100, 2)
			go func() {
				if err := session.StreamAudio(ctx, audioCapture, session.AudioStream()); err != nil && ctx.Err() == nil {
					log.Printf("audio streaming error: %v", err)
				}
			}()
			log.Println("audio streaming started")
		}
	} else if !cfg.NoAudio {
		log.Println("audio disabled (receiver did not provide audio ports)")
	}

	s.listener.OnState(StateStreaming, "")
	// The receiver can only start decoding at a keyframe.
	s.listener.OnKeyframeNeeded()
	if err := session.StreamFrames(ctx, capture, 0); err != nil && ctx.Err() == nil {
		return fmt.Errorf("streaming error: %w", err)
	}
	return nil
}

// LogSink receives doubletake log output one line at a time.
type LogSink interface {
	OnLog(line string)
}

// RedirectLogs sends all doubletake logging to sink. Call once at app start;
// later calls replace the destination.
func RedirectLogs(sink LogSink) {
	log.SetFlags(log.Ltime | log.Lmicroseconds)
	log.SetOutput(&lineWriter{listener: sink})
}

type lineWriter struct {
	mu       sync.Mutex
	listener LogSink
	partial  bytes.Buffer
}

func (w *lineWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.partial.Write(p)
	for {
		data := w.partial.Bytes()
		i := bytes.IndexByte(data, '\n')
		if i < 0 {
			break
		}
		line := strings.TrimRight(string(data[:i]), "\r")
		w.partial.Next(i + 1)
		if w.listener != nil {
			w.listener.OnLog(line)
		}
	}
	return len(p), nil
}

// Version describes this build for the log.
func Version() string {
	return "doubletake-android milestone 1"
}
