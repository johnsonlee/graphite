// Package web embeds the existing Explorer frontend unchanged.
package web

import (
	"embed"
	"io/fs"
)

//go:generate python3 generate.py

//go:embed assets/*
var files embed.FS

func Files() fs.FS {
	sub, err := fs.Sub(files, "assets")
	if err != nil {
		panic(err)
	}
	return sub
}
