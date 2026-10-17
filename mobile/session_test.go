package mobile

import (
	"bytes"
	"context"
	"io"
	"log"
	"net"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"doubletake/internal/airplay"
)

// fakeHost plays the Android app: it answers code prompts and starts pushing
// synthetic H.264 and PCM once the session asks for them.
type fakeHost struct {
	t       *testing.T
	code    string
	session *Session

	mu         sync.Mutex
	states     []string
	codeKinds  []string
	keyframes  int
	videoStart chan [2]int
	audioStart chan struct{}
	stop       chan struct{}
	logs       bytes.Buffer
}

func newFakeHost(t *testing.T, code string) *fakeHost {
	return &fakeHost{
		t:          t,
		code:       code,
		videoStart: make(chan [2]int, 1),
		audioStart: make(chan struct{}, 1),
		stop:       make(chan struct{}),
	}
}

func (h *fakeHost) OnLog(line string) {
	h.mu.Lock()
	h.logs.WriteString(line + "\n")
	h.mu.Unlock()
}

func (h *fakeHost) OnState(state, detail string) {
	h.mu.Lock()
	h.states = append(h.states, state)
	if state == StateNeedCode {
		h.codeKinds = append(h.codeKinds, detail)
	}
	h.mu.Unlock()
	if state == StateNeedCode && h.code != "" {
		go h.session.SubmitCode(h.code)
	}
}

func (h *fakeHost) OnVideoFormat(width, height, fps int) {
	h.videoStart <- [2]int{width, height}
}

func (h *fakeHost) OnKeyframeNeeded() {
	h.mu.Lock()
	h.keyframes++
	h.mu.Unlock()
}

func (h *fakeHost) OnAudioFormat(sampleRate, channels int) {
	if sampleRate != 44100 || channels != 2 {
		h.t.Errorf("audio format %d Hz x%d", sampleRate, channels)
	}
	h.audioStart <- struct{}{}
}

var (
	testSPS = []byte{0x67, 0x42, 0x00, 0x1f, 0xf8, 0x0a, 0x00, 0xb7, 0x20}
	testPPS = []byte{0x68, 0xce, 0x3c, 0x80}
)

func annexB(nals ...[]byte) []byte {
	var out []byte
	for _, nal := range nals {
		out = append(out, 0, 0, 0, 1)
		out = append(out, nal...)
	}
	return out
}

// pump pushes 30 fps video (IDR every 15 frames) and 352-sample PCM blocks
// with ages that look like a real capture pipeline.
func (h *fakeHost) pump() {
	go func() {
		select {
		case <-h.videoStart:
		case <-h.stop:
			return
		}
		ticker := time.NewTicker(33 * time.Millisecond)
		defer ticker.Stop()
		for frame := 0; ; frame++ {
			var au []byte
			if frame%15 == 0 {
				au = annexB(testSPS, testPPS, append([]byte{0x65, 0x88}, bytes.Repeat([]byte{0x11}, 2000)...))
			} else {
				au = annexB(append([]byte{0x41, 0x9a}, bytes.Repeat([]byte{0x22}, 300)...))
			}
			_ = h.session.PushVideo(au, frame%15 == 0, int64(5*time.Millisecond))
			select {
			case <-ticker.C:
			case <-h.stop:
				return
			}
		}
	}()
	go func() {
		select {
		case <-h.audioStart:
		case <-h.stop:
			return
		}
		ticker := time.NewTicker(8 * time.Millisecond)
		defer ticker.Stop()
		pcm := make([]byte, 352*4)
		for i := range pcm {
			pcm[i] = byte(i)
		}
		for {
			_ = h.session.PushAudio(pcm, int64(2*time.Millisecond))
			select {
			case <-ticker.C:
			case <-h.stop:
				return
			}
		}
	}()
}

func (h *fakeHost) has(state string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, s := range h.states {
		if s == state {
			return true
		}
	}
	return false
}

func startReceiver(t *testing.T, cfg airplay.ReceiverConfig) (*airplay.ReceiverServer, string, int) {
	t.Helper()
	cfg.ListenAddress = "127.0.0.1:0"
	cfg.Logger = log.New(io.Discard, "", 0)
	server, err := airplay.NewReceiverServer(cfg)
	if err != nil {
		t.Fatalf("receiver: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = server.Serve(ctx) }()
	t.Cleanup(func() { cancel(); _ = server.Close() })
	host, portText, _ := net.SplitHostPort(server.Addr().String())
	port, _ := strconv.Atoi(portText)
	return server, host, port
}

func runUntilMedia(t *testing.T, server *airplay.ReceiverServer, cfg *Config, code string) *fakeHost {
	t.Helper()
	host := newFakeHost(t, code)
	RedirectLogs(host)
	host.session = NewSession(cfg, host)
	host.pump()
	before := server.Stats()
	host.session.Start()
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		stats := server.Stats()
		stats.VideoPackets -= before.VideoPackets
		stats.AudioPackets -= before.AudioPackets
		if stats.VideoPackets >= 20 && (cfg.NoAudio || stats.AudioPackets >= 20) {
			break
		}
		if host.has(StateFailed) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	close(host.stop)
	host.session.Stop()
	stats := server.Stats()
	stats.VideoPackets -= before.VideoPackets
	stats.AudioPackets -= before.AudioPackets
	if stats.VideoPackets < 20 || (!cfg.NoAudio && stats.AudioPackets < 20) || stats.VideoCryptoErrors != 0 {
		host.mu.Lock()
		logs := host.logs.String()
		states := host.states
		host.mu.Unlock()
		t.Fatalf("media did not flow: stats=%+v states=%v\nlogs:\n%s", stats, states, logs)
	}
	if !host.has(StateStreaming) || !host.has(StateStopped) {
		t.Fatalf("states = %v\nlogs:\n%s", host.states, host.logs.String())
	}
	return host
}

func TestSessionPairsWithPINThenReusesCredentials(t *testing.T) {
	for _, profile := range []airplay.ReceiverProfile{airplay.ReceiverProfileLG, airplay.ReceiverProfileRoku} {
		t.Run(string(profile), func(t *testing.T) {
			server, addr, port := startReceiver(t, airplay.ReceiverConfig{
				Profile: profile,
				Auth:    airplay.ReceiverAuthPIN,
				Code:    "4321",
			})
			cfg := NewConfig(addr)
			cfg.Port = port
			cfg.CredentialsPath = filepath.Join(t.TempDir(), "credentials.json")

			first := runUntilMedia(t, server, cfg, "4321")
			if len(first.codeKinds) != 1 || first.codeKinds[0] != CodePIN {
				t.Fatalf("first session code prompts = %v, want one PIN", first.codeKinds)
			}
			if first.keyframes == 0 {
				t.Fatal("session never requested a keyframe at stream start")
			}

			second := runUntilMedia(t, server, cfg, "")
			if len(second.codeKinds) != 0 {
				t.Fatalf("second session prompted %v despite saved credentials", second.codeKinds)
			}
		})
	}
}

func TestSessionStopWhileWaitingForCode(t *testing.T) {
	_, addr, port := startReceiver(t, airplay.ReceiverConfig{
		Profile: airplay.ReceiverProfileLG,
		Auth:    airplay.ReceiverAuthPIN,
		Code:    "4321",
	})
	cfg := NewConfig(addr)
	cfg.Port = port
	cfg.CredentialsPath = filepath.Join(t.TempDir(), "credentials.json")
	host := newFakeHost(t, "")
	host.session = NewSession(cfg, host)
	// Do not answer the prompt: Stop must still return promptly.
	host.code = ""
	host.session.Start()
	deadline := time.Now().Add(5 * time.Second)
	for !host.has(StateNeedCode) && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	started := time.Now()
	host.session.Stop()
	if time.Since(started) > 2*time.Second {
		t.Fatal("Stop blocked")
	}
	for !host.has(StateStopped) && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !host.has(StateStopped) {
		t.Fatalf("states = %v, want stopped", host.states)
	}
}
