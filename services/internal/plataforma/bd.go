package plataforma

import (
	"context"
	"fmt"
	"hash/fnv"
	"io/fs"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ConectarBD abre el pool y espera a que Postgres responda (hasta 60 s, para
// arrancar junto con el contenedor de la base).
func ConectarBD(ctx context.Context, dsn string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("DSN inválido: %w", err)
	}
	cfg.MaxConns = 15
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = 30 * time.Second
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	limite := time.Now().Add(60 * time.Second)
	for {
		if err = pool.Ping(ctx); err == nil {
			return pool, nil
		}
		if time.Now().After(limite) {
			pool.Close()
			return nil, fmt.Errorf("Postgres no responde: %w", err)
		}
		time.Sleep(time.Second)
	}
}

// Migrar aplica en orden los archivos NNN_nombre.sql de dir que falten. Usa un
// candado de Postgres: si arrancan varias réplicas, solo una migra.
func Migrar(ctx context.Context, pool *pgxpool.Pool, esquema string, archivos fs.FS) error {
	nombres, err := fs.Glob(archivos, "*.sql")
	if err != nil {
		return err
	}
	sort.Strings(nombres)
	h := fnv.New64a()
	h.Write([]byte("migrar:" + esquema))
	candado := int64(h.Sum64() >> 1)

	return pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock($1)", candado); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `CREATE TABLE IF NOT EXISTS migraciones (
			version INTEGER PRIMARY KEY, nombre TEXT NOT NULL, aplicada TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
			return err
		}
		for _, n := range nombres {
			v, err := strconv.Atoi(strings.SplitN(n, "_", 2)[0])
			if err != nil {
				return fmt.Errorf("migración sin número: %s", n)
			}
			var ya bool
			if err := tx.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM migraciones WHERE version = $1)", v).Scan(&ya); err != nil {
				return err
			}
			if ya {
				continue
			}
			sql, err := fs.ReadFile(archivos, n)
			if err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, string(sql)); err != nil {
				return fmt.Errorf("migración %s: %w", n, err)
			}
			if _, err := tx.Exec(ctx, "INSERT INTO migraciones (version, nombre) VALUES ($1, $2)", v, n); err != nil {
				return err
			}
		}
		return nil
	})
}
