'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { pipeline } = require('node:stream');
const root = __dirname;
const port = Number(process.env.AGROSENSE_FRONT_PORT || 5500);
const backend = new URL(process.env.AGROSENSE_BACKEND_URL || 'http://127.0.0.1:8080');
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Porta do frontend inválida.');
if (backend.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(backend.hostname) || backend.username || backend.password || backend.pathname !== '/' || backend.search || backend.hash) {
  throw new Error('AGROSENSE_BACKEND_URL deve apontar para o backend HTTP local, sem caminho.');
}
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
const publicFiles = new Set(['index.html', 'login.html', 'login.js', 'login.css', 'login-scene.js']);
const publicDirs = new Set(['js', 'css', 'assets', 'vendor']);

function fail(res, status, codigo, mensagem) {
  if (res.headersSent) { res.destroy(); return; }
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify({ codigo, mensagem }));
}

const server = http.createServer((req, res) => {
  const hosts = new Set([`localhost:${port}`, `127.0.0.1:${port}`]);
  if (!hosts.has(req.headers.host)) return fail(res, 403, 'ORIGEM_INVALIDA', 'Endereço local inválido.');
  const origin = `http://${req.headers.host}`;
  if (req.headers.origin && req.headers.origin !== origin) return fail(res, 403, 'ORIGEM_INVALIDA', 'Origem não permitida.');
  if (req.headers['sec-fetch-site'] === 'cross-site') return fail(res, 403, 'ORIGEM_INVALIDA', 'Origem não permitida.');

  let decoded;
  try { decoded = decodeURIComponent(req.url.split('?')[0]); }
  catch (_) { return fail(res, 400, 'CAMINHO_INVALIDO', 'Caminho inválido.'); }
  if (!decoded.startsWith('/') || decoded.includes('\\') || decoded.includes('\0') || decoded.split('/').some(s => s === '..' || s === '.')) {
    return fail(res, 400, 'CAMINHO_INVALIDO', 'Caminho inválido.');
  }
  const proxy = decoded.startsWith('/api/') || decoded === '/login' || decoded === '/logout';
  if (proxy) {
    if (!['GET', 'POST', 'PUT', 'PATCH', 'OPTIONS'].includes(req.method)) return fail(res, 405, 'METODO_INVALIDO', 'Método não permitido.');
    if (['POST', 'PUT', 'PATCH'].includes(req.method)) {
      let sameReferer = false;
      try { sameReferer = new URL(req.headers.referer).origin === origin; } catch (_) {}
      if (!req.headers.origin && !sameReferer) return fail(res, 403, 'ORIGEM_INVALIDA', 'Origem não permitida.');
    }
    const headers = { host: backend.host };
    for (const name of ['accept', 'content-type', 'content-length', 'cookie', 'x-csrf-token']) {
      if (req.headers[name] !== undefined) headers[name] = req.headers[name];
    }
    if (req.headers.origin) headers.origin = backend.origin;
    const upstream = http.request({ hostname: backend.hostname, port: backend.port || 80, method: req.method, path: req.url, headers }, incoming => {
      const outgoing = { ...incoming.headers, 'cache-control': 'no-store' };
      for (const name of ['connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'access-control-allow-origin', 'access-control-allow-credentials']) delete outgoing[name];
      res.writeHead(incoming.statusCode, outgoing);
      pipeline(incoming, res, () => {});
    });
    upstream.setTimeout(12000, () => upstream.destroy(new Error('Timeout')));
    upstream.on('error', () => fail(res, 502, 'BACKEND_INDISPONIVEL', 'Não foi possível conectar ao backend local.'));
    req.on('aborted', () => upstream.destroy());
    res.on('close', () => { if (!res.writableEnded) upstream.destroy(); });
    req.pipe(upstream);
    return;
  }
  if (!['GET', 'HEAD'].includes(req.method)) return fail(res, 405, 'METODO_INVALIDO', 'Método não permitido.');
  const relative = decoded === '/' ? 'login.html' : decoded.slice(1);
  const extension = path.extname(relative);
  if (!mime[extension] || (!publicFiles.has(relative) && !publicDirs.has(relative.split('/')[0]))) return fail(res, 404, 'NAO_ENCONTRADO', 'Arquivo não encontrado.');
  const file = path.resolve(root, relative);
  if (!file.startsWith(root + path.sep)) return fail(res, 404, 'NAO_ENCONTRADO', 'Arquivo não encontrado.');
  fs.stat(file, (err, stat) => {
    if (err || !stat.isFile()) return fail(res, 404, 'NAO_ENCONTRADO', 'Arquivo não encontrado.');
    res.writeHead(200, { 'Content-Type': mime[extension], 'Content-Length': stat.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'same-origin' });
    if (req.method === 'HEAD') return res.end();
    pipeline(fs.createReadStream(file), res, () => {});
  });
});

server.on('error', err => {
  console.error(err.code === 'EADDRINUSE' ? `A porta ${port} está ocupada. Feche o outro servidor ou configure AGROSENSE_FRONT_PORT.` : 'Não foi possível iniciar o frontend.');
  process.exitCode = 1;
});
server.listen(port, '127.0.0.1', () => console.log(`AgroSense: http://localhost:${port}/login.html\nBackend: ${backend.origin}\nMantenha este terminal aberto. Ctrl+C para encerrar.`));
