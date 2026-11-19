// Prepara la carpeta www/: copia la app web e inyecta el puente nativo (youtubei.js + plugins).
import { build } from 'esbuild';
import { cpSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';

rmSync('www', { recursive: true, force: true });
mkdirSync('www', { recursive: true });
cpSync('web', 'www', { recursive: true });

await build({
  entryPoints: ['src/native.js'],
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: ['chrome100'],
  minify: true,
  outfile: 'www/native.bundle.js',
  logLevel: 'info',
});

let html = readFileSync('www/index.html', 'utf8');
html = html.replace('<script>\n\'use strict\';', '<script src="native.bundle.js"></script>\n<script>\n\'use strict\';');
if (!html.includes('native.bundle.js')) throw new Error('No se pudo inyectar native.bundle.js');
writeFileSync('www/index.html', html);
console.log('www listo');
