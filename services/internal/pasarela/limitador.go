package pasarela

import (
	"sync"
	"time"
)

// limitador es una cubeta de fichas por IP, en memoria. Con varias réplicas de
// la pasarela cada una cuenta por separado; para escalar, mover a Redis.
type limitador struct {
	mu      sync.Mutex
	tasa    float64 // fichas por segundo
	rafaga  float64
	cubetas map[string]*cubeta
	limpio  time.Time
}

type cubeta struct {
	fichas float64
	visto  time.Time
}

func nuevoLimitador(tasa, rafaga float64) *limitador {
	return &limitador{tasa: tasa, rafaga: rafaga, cubetas: map[string]*cubeta{}, limpio: time.Now()}
}

func (l *limitador) permitir(clave string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	ahora := time.Now()
	if ahora.Sub(l.limpio) > time.Minute {
		for k, c := range l.cubetas {
			if ahora.Sub(c.visto) > 10*time.Minute {
				delete(l.cubetas, k)
			}
		}
		l.limpio = ahora
	}
	c, ok := l.cubetas[clave]
	if !ok {
		if len(l.cubetas) > 200_000 { // tope de memoria ante muchas IPs falsas
			return false
		}
		c = &cubeta{fichas: l.rafaga, visto: ahora}
		l.cubetas[clave] = c
	}
	c.fichas = min(l.rafaga, c.fichas+ahora.Sub(c.visto).Seconds()*l.tasa)
	c.visto = ahora
	if c.fichas < 1 {
		return false
	}
	c.fichas--
	return true
}
