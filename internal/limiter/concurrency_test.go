package limiter

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestKeyLimiterEntriesAreReleased(t *testing.T) {
	l := NewConcurrencyLimiter(8, 2, time.Second)
	for i := 0; i < 500; i++ {
		release, err := l.Acquire(context.Background(), "sk-test")
		if err != nil {
			t.Fatalf("acquire %d: %v", i, err)
		}
		release()
	}
	if got := l.KeyLimiterCount(); got != 0 {
		t.Fatalf("KeyLimiterCount = %d, want 0", got)
	}
}

func TestReleaseIsIdempotent(t *testing.T) {
	l := NewConcurrencyLimiter(1, 1, time.Second)
	release, err := l.Acquire(context.Background(), "k")
	if err != nil {
		t.Fatalf("acquire: %v", err)
	}
	release()
	release()

	// The single global slot must still be usable after the second release.
	release2, err := l.Acquire(context.Background(), "k")
	if err != nil {
		t.Fatalf("second acquire: %v", err)
	}
	release2()
}

func TestPerKeyLimit(t *testing.T) {
	l := NewConcurrencyLimiter(10, 1, 50*time.Millisecond)
	release, err := l.Acquire(context.Background(), "k")
	if err != nil {
		t.Fatalf("acquire: %v", err)
	}
	defer release()

	if _, err := l.Acquire(context.Background(), "k"); !errors.Is(err, ErrKeyLimitExceeded) {
		t.Fatalf("second acquire error = %v, want ErrKeyLimitExceeded", err)
	}
}

func TestGlobalLimit(t *testing.T) {
	l := NewConcurrencyLimiter(1, 10, 50*time.Millisecond)
	release, err := l.Acquire(context.Background(), "a")
	if err != nil {
		t.Fatalf("acquire: %v", err)
	}
	defer release()

	if _, err := l.Acquire(context.Background(), "b"); !errors.Is(err, ErrGlobalLimitExceeded) {
		t.Fatalf("second acquire error = %v, want ErrGlobalLimitExceeded", err)
	}
	// The failed attempt must not leak the per-key entry for "b".
	if got := l.KeyLimiterCount(); got != 1 {
		t.Fatalf("KeyLimiterCount = %d, want 1", got)
	}
}

func TestContextCancellationReleasesSlots(t *testing.T) {
	l := NewConcurrencyLimiter(1, 1, time.Minute)
	release, err := l.Acquire(context.Background(), "k")
	if err != nil {
		t.Fatalf("acquire: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := l.Acquire(ctx, "k"); !errors.Is(err, context.Canceled) {
		t.Fatalf("acquire error = %v, want context.Canceled", err)
	}

	release()
	if got := l.KeyLimiterCount(); got != 0 {
		t.Fatalf("KeyLimiterCount = %d, want 0", got)
	}
}

func TestConcurrentAcquireRelease(t *testing.T) {
	l := NewConcurrencyLimiter(4, 2, 2*time.Second)
	var wg sync.WaitGroup
	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			key := fmt.Sprintf("k%d", i%3)
			release, err := l.Acquire(context.Background(), key)
			if err != nil {
				t.Errorf("acquire: %v", err)
				return
			}
			release()
		}(i)
	}
	wg.Wait()
	if got := l.KeyLimiterCount(); got != 0 {
		t.Fatalf("KeyLimiterCount = %d, want 0", got)
	}
}
