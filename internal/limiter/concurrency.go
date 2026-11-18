package limiter

import (
	"context"
	"errors"
	"sync"
	"time"
)

var (
	ErrGlobalLimitExceeded = errors.New("global concurrent request limit exceeded")
	ErrKeyLimitExceeded    = errors.New("per-key concurrent request limit exceeded")

	errQueueTimeout = errors.New("queue timeout")
)

type keyLimiter struct {
	sem chan struct{}
	// refs counts the requests that hold or wait on this limiter. The entry is
	// dropped from the map once the count returns to zero, so keys created and
	// revoked repeatedly do not accumulate here forever.
	refs int
}

type ConcurrencyLimiter struct {
	globalSem     chan struct{}
	perKeyLimit   int
	queueTimeout  time.Duration
	keyLimitersMu sync.Mutex
	keyLimiters   map[string]*keyLimiter
}

func NewConcurrencyLimiter(maxGlobal int, maxPerKey int, queueTimeout time.Duration) *ConcurrencyLimiter {
	if maxGlobal <= 0 {
		maxGlobal = 1024
	}
	if maxPerKey <= 0 {
		maxPerKey = 64
	}

	return &ConcurrencyLimiter{
		globalSem:    make(chan struct{}, maxGlobal),
		perKeyLimit:  maxPerKey,
		queueTimeout: queueTimeout,
		keyLimiters:  make(map[string]*keyLimiter),
	}
}

// getKeyLimiter returns the limiter for keyID and takes one reference on it.
// Every returned limiter must be handed back with dropKeyLimiter.
func (l *ConcurrencyLimiter) getKeyLimiter(keyID string) *keyLimiter {
	l.keyLimitersMu.Lock()
	defer l.keyLimitersMu.Unlock()

	kl, exists := l.keyLimiters[keyID]
	if !exists {
		kl = &keyLimiter{sem: make(chan struct{}, l.perKeyLimit)}
		l.keyLimiters[keyID] = kl
	}
	kl.refs++
	return kl
}

func (l *ConcurrencyLimiter) dropKeyLimiter(keyID string, kl *keyLimiter) {
	l.keyLimitersMu.Lock()
	defer l.keyLimitersMu.Unlock()

	kl.refs--
	if kl.refs > 0 {
		return
	}
	if current, ok := l.keyLimiters[keyID]; ok && current == kl {
		delete(l.keyLimiters, keyID)
	}
}

// KeyLimiterCount reports how many per-key limiters are currently tracked.
func (l *ConcurrencyLimiter) KeyLimiterCount() int {
	l.keyLimitersMu.Lock()
	defer l.keyLimitersMu.Unlock()
	return len(l.keyLimiters)
}

// waitSem takes one slot from sem, honouring the queue timeout and the caller's
// context. It reports errQueueTimeout when the timeout elapses first.
func (l *ConcurrencyLimiter) waitSem(ctx context.Context, sem chan struct{}) error {
	select {
	case sem <- struct{}{}:
		return nil
	default:
	}

	if l.queueTimeout <= 0 {
		return errQueueTimeout
	}
	timer := time.NewTimer(l.queueTimeout)
	defer timer.Stop()
	select {
	case sem <- struct{}{}:
		return nil
	case <-timer.C:
		return errQueueTimeout
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (l *ConcurrencyLimiter) Acquire(ctx context.Context, keyID string) (func(), error) {
	// Step 1: take the per-key slot.
	var (
		kl      *keyLimiter
		haveKey bool
	)
	if keyID != "" {
		kl = l.getKeyLimiter(keyID)
		haveKey = true
		if err := l.waitSem(ctx, kl.sem); err != nil {
			l.dropKeyLimiter(keyID, kl)
			if errors.Is(err, errQueueTimeout) {
				return nil, ErrKeyLimitExceeded
			}
			return nil, err
		}
	}

	// Step 2: take the global slot, handing the per-key slot back on failure.
	if err := l.waitSem(ctx, l.globalSem); err != nil {
		if haveKey {
			<-kl.sem
			l.dropKeyLimiter(keyID, kl)
		}
		if errors.Is(err, errQueueTimeout) {
			return nil, ErrGlobalLimitExceeded
		}
		return nil, err
	}

	// Releasing twice would drain another request's slot, so make it idempotent.
	var once sync.Once
	release := func() {
		once.Do(func() {
			<-l.globalSem
			if haveKey {
				<-kl.sem
				l.dropKeyLimiter(keyID, kl)
			}
		})
	}
	return release, nil
}
