package airplay

import (
	"testing"
	"time"
)

func TestParseInitialVolume(t *testing.T) {
	for _, test := range []struct {
		in   string
		want InitialVolumeSetting
		ok   bool
	}{
		{"", InitialVolumeSetting{}, true},
		{"keep", InitialVolumeSetting{Keep: true}, true},
		{" KEEP ", InitialVolumeSetting{Keep: true}, true},
		{"-20", InitialVolumeSetting{DB: -20}, true},
		{"-12.5 dB", InitialVolumeSetting{DB: -12.5}, true},
		{"0", InitialVolumeSetting{}, true},
		{"5", InitialVolumeSetting{}, false},
		{"-31", InitialVolumeSetting{}, false},
		{"loud", InitialVolumeSetting{}, false},
	} {
		got, err := ParseInitialVolume(test.in)
		if (err == nil) != test.ok || (test.ok && got != test.want) {
			t.Errorf("ParseInitialVolume(%q) = %+v, %v", test.in, got, err)
		}
	}
	if body := string(volumeBodyDB(-20)); body != "volume: -20.000000\r\n" {
		t.Errorf("volume body = %q", body)
	}
}

func TestSetupMirrorInitialVolume(t *testing.T) {
	for _, test := range []struct {
		setting   string
		wantCount uint64
		wantBody  string
	}{
		{"keep", 0, ""},
		{"-20", 2, "volume: -20.000000\r\n"},
		{"", 2, "volume: 0.000000\r\n"},
	} {
		t.Run("volume="+test.setting, func(t *testing.T) {
			server, client, ctx := newReceiverServerTestPair(t, ReceiverConfig{Profile: ReceiverProfileLG})
			if err := client.Pair(ctx, ""); err != nil {
				t.Fatalf("pair: %v", err)
			}
			session, err := client.SetupMirror(ctx, StreamConfig{InitialVolume: test.setting})
			if err != nil {
				t.Fatalf("setup mirror: %v", err)
			}
			defer session.Close()
			deadline := time.Now().Add(time.Second)
			stats := server.Stats()
			for stats.ParameterRequests < test.wantCount && time.Now().Before(deadline) {
				time.Sleep(time.Millisecond)
				stats = server.Stats()
			}
			if stats.ParameterRequests != test.wantCount || stats.LastParameterBody != test.wantBody {
				t.Fatalf("SET_PARAMETER count=%d last=%q, want %d %q",
					stats.ParameterRequests, stats.LastParameterBody, test.wantCount, test.wantBody)
			}
		})
	}

	_, client, ctx := newReceiverServerTestPair(t, ReceiverConfig{Profile: ReceiverProfileLG})
	if err := client.Pair(ctx, ""); err != nil {
		t.Fatalf("pair: %v", err)
	}
	if _, err := client.SetupMirror(ctx, StreamConfig{InitialVolume: "11"}); err == nil {
		t.Fatal("setup accepted an out-of-range initial volume")
	}
}
