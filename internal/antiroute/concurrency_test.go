package antiroute

import (
	"strconv"
	"sync"
	"testing"
)

// RestoreString and RestoreBytes used to read s.pairs outside the mutex while
// record() appended to it and re-sorted the same backing array in place. With
// -race that reports a DATA RACE between the restore loop and sort.SliceStable;
// without -race it can hand back a half-sorted snapshot, so a term gets restored
// with the wrong neighbour's spelling.
//
// Both sides run the same session, which is what a streaming response does: the
// writer keeps discovering terms while the reader keeps restoring chunks.
func TestRestoreDoesNotRaceWithRecord(t *testing.T) {
	g, err := New(Options{Split: true})
	if err != nil {
		t.Fatalf("new guard: %v", err)
	}
	s := g.NewSession()
	if s == nil {
		t.Fatal("no session")
	}

	const rounds = 4000
	var wg sync.WaitGroup
	wg.Add(3)
	go func() {
		defer wg.Done()
		for i := 0; i < rounds; i++ {
			s.record(pair{masked: "mask-" + strconv.Itoa(i), original: "orig-" + strconv.Itoa(i)}, 1, false)
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < rounds; i++ {
			_ = s.RestoreString("mask-1 mask-2 mask-3")
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < rounds; i++ {
			_ = s.RestoreBytes([]byte(`{"t":"mask-1"}`))
		}
	}()
	wg.Wait()
}

// Duplicate terms must not multiply: record() dedupes by masked form, and a
// restore running concurrently must still return a single replacement.
func TestRestoreIsStableUnderConcurrentRecording(t *testing.T) {
	g, err := New(Options{Split: true})
	if err != nil {
		t.Fatalf("new guard: %v", err)
	}
	s := g.NewSession()
	// Warm up: before the first record the pair list is empty and a restore
	// legitimately returns its input. The assertions below are about duplicates
	// staying single, not about ordering.
	s.record(pair{masked: "同一-掩码", original: "原文"}, 1, false)

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 2000; i++ {
			s.record(pair{masked: "同一-掩码", original: "原文"}, 1, false)
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 2000; i++ {
			if got := s.RestoreString("同一-掩码"); got != "原文" {
				t.Errorf("restore returned %q, want 原文", got)
				return
			}
		}
	}()
	wg.Wait()
}
