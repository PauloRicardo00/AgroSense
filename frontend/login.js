/* AgroSense · Login.
 * PONTO DE INTEGRAÇÃO: este arquivo NÃO autentica ninguém. Ele chama window.AgroSenseAuth.login({ email, password }),
 * que deve ser fornecida pelo mecanismo de autenticação já existente no projeto e retornar (ou rejeitar) assim:
 *   resolve({ perfil: 'operador' | 'tecnico' | 'gestor' })   // perfil vindo do servidor/sessão, nunca da interface
 *   reject({ code: 'INVALID_CREDENTIALS' })                   // ou qualquer outro erro
 * Ajuste ROUTES para os caminhos reais dos painéis.
 */
(() => {
  'use strict';

  const ROUTES = { admin: 'index.html', operador: 'index.html', tecnico: 'index.html', gestor: 'index.html' };
  const MESSAGES = {
    INVALID_CREDENTIALS: 'E-mail ou senha incorretos. Confira os dados e tente novamente.',
    NOT_CONNECTED: 'A autenticação ainda não está conectada a esta tela.',
    PROFILE: 'Sua conta não tem um perfil válido. Procure o administrador do sistema.',
    DEFAULT: 'Não foi possível entrar agora. Tente novamente em instantes.'
  };
  const FIELD_MESSAGES = {
    email: { valueMissing: 'Informe seu e-mail.', typeMismatch: 'Informe um e-mail válido.' },
    senha: { valueMissing: 'Informe sua senha.' }
  };

  const $ = (s) => document.querySelector(s);
  const root = document.documentElement;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const form = $('#login-form'), email = $('#email'), pass = $('#senha');
  const btn = $('#entrar'), label = $('.ag-btn__label'), alertBox = $('#form-status'), srStatus = $('#sr-status');
  const idleText = label.textContent;

  /* Logo: se o arquivo não carregar, mostra o nome em texto (sem ícone de imagem quebrada) */
  const logo = $('.ag-logo');
  const logoFallback = () => {
    const t = document.createElement('p');
    t.className = 'ag-logo ag-logo--text'; t.textContent = 'AgroSense'; t.setAttribute('data-r', ''); t.style.setProperty('--d', '1');
    logo.replaceWith(t);
  };
  if (logo.complete && logo.naturalWidth === 0) logoFallback(); else logo.addEventListener('error', logoFallback, { once: true });

  /* Título: entrada palavra a palavra (adaptação de Blur Text) */
  const title = $('.ag-title');
  title.innerHTML = title.textContent.trim().split(/\s+/).map((w, i) => `<span class="w" style="--i:${i}">${w}</span>`).join(' ');

  /* Início da coreografia, depois da fonte (máx. 500 ms) */
  const start = () => requestAnimationFrame(() => { root.classList.add('ag-ready'); window.AgroScene && AgroScene.run(); });
  Promise.race([document.fonts && document.fonts.ready, new Promise((r) => setTimeout(r, 500))]).then(start);

  /* Mostrar/ocultar senha */
  const toggle = $('#toggle-senha');
  toggle.addEventListener('click', () => {
    const show = pass.type === 'password';
    pass.type = show ? 'text' : 'password';
    toggle.setAttribute('aria-pressed', String(show));
    toggle.setAttribute('aria-label', show ? 'Ocultar senha' : 'Mostrar senha');
  });

  /* Validação por campo */
  function check(el) {
    const box = $('#' + el.id + '-erro');
    let msg = '';
    if (!el.validity.valid) {
      const key = Object.keys(FIELD_MESSAGES[el.id]).find((k) => el.validity[k]);
      msg = FIELD_MESSAGES[el.id][key] || 'Verifique este campo.';
    }
    box.textContent = msg;
    el.setAttribute('aria-invalid', String(!!msg));
    el.closest('.ag-field').classList.toggle('has-error', !!msg);
    return !msg;
  }
  [email, pass].forEach((el) => {
    el.addEventListener('blur', () => { if (el.value) check(el); });
    el.addEventListener('input', () => { if (el.getAttribute('aria-invalid') === 'true') check(el); });
  });

  /* Estados do botão */
  function setState(state) {
    btn.dataset.state = state;
    btn.setAttribute('aria-disabled', String(state === 'loading'));
    label.textContent = { idle: idleText, loading: 'Entrando…', success: 'Acesso confirmado' }[state];
    srStatus.textContent = { idle: '', loading: 'Verificando seus dados.', success: 'Acesso confirmado. Abrindo seu ambiente.' }[state];
  }
  function shake(el) {
    if (reduce) return;
    el.classList.remove('is-shaking'); void el.offsetWidth; el.classList.add('is-shaking');
    el.addEventListener('animationend', () => el.classList.remove('is-shaking'), { once: true });
  }
  function showError(text) { alertBox.textContent = text; alertBox.hidden = false; }
  const offlineMessage = 'O navegador está sem conexão. Confira sua rede e tente entrar novamente.';
  function updateConnection() {
    if (!navigator.onLine) showError(offlineMessage);
    else if (alertBox.textContent === offlineMessage) alertBox.hidden = true;
  }
  window.addEventListener('offline', updateConnection);
  window.addEventListener('online', updateConnection);
  updateConnection();
  window.AgroSenseAuth?.refresh().then(user => {
    if (user) location.replace('index.html');
  }).catch(err => {
    if (btn.dataset.state === 'idle' && alertBox.hidden) showError(err.message || MESSAGES.DEFAULT);
  });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (btn.dataset.state === 'loading' || btn.dataset.state === 'success') return;
    alertBox.hidden = true;

    const results = [email, pass].map(check);
    if (results.includes(false)) {
      const invalid = [email, pass].filter((el) => el.getAttribute('aria-invalid') === 'true');
      invalid[0].focus();
      return invalid.forEach((el) => shake(el.closest('.ag-field')));
    }

    setState('loading');
    try {
      const login = window.AgroSenseAuth && window.AgroSenseAuth.login;
      if (typeof login !== 'function') throw { code: 'NOT_CONNECTED' };
      const result = await login({ email: email.value.trim(), password: pass.value });
      const destino = result && Object.prototype.hasOwnProperty.call(ROUTES, result.perfil) ? ROUTES[result.perfil] : null;
      if (!destino) throw { code: 'PROFILE' };
      setState('success');
      setTimeout(() => location.assign(destino), reduce ? 300 : 700);
    } catch (err) {
      setState('idle');
      showError(err.message || MESSAGES[err.code] || MESSAGES.DEFAULT);
      shake($('.ag-actions'));
      if (err && err.code === 'CREDENCIAIS_INVALIDAS') { pass.focus(); pass.select(); }
    }
  });

  // Ajusta inclusive mensagens de erro e teclado virtual, mantendo a página fixa.
  const loginForm = $('.ag-form'), loginColumn = $('.ag-form-col');
  if (loginForm && loginColumn) {
    const fitLogin = () => {
      const style = getComputedStyle(loginColumn);
      const available = loginColumn.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom);
      loginForm.style.setProperty('--login-scale', Math.min(1, available / Math.max(1, loginForm.scrollHeight)));
    };
    const resize = new ResizeObserver(fitLogin);
    resize.observe(loginColumn); resize.observe(loginForm);
    new MutationObserver(fitLogin).observe(loginForm,{subtree:true,childList:true,attributes:true,attributeFilter:['hidden']});
    document.fonts.ready.then(fitLogin);
    window.visualViewport?.addEventListener('resize',fitLogin);
    fitLogin();
  }

  /* Parallax suave: só com mouse e sem preferência por movimento reduzido */
  const visual = $('.ag-visual');
  if (visual && !reduce && matchMedia('(hover: hover) and (pointer: fine)').matches) {
    let raf = 0;
    const set = (x, y) => { visual.style.setProperty('--px', x); visual.style.setProperty('--py', y); };
    visual.addEventListener('pointermove', (e) => {
      const r = visual.getBoundingClientRect();
      const x = ((e.clientX - r.left) / r.width - 0.5).toFixed(3), y = ((e.clientY - r.top) / r.height - 0.5).toFixed(3);

      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => set(x, y));
    });
    visual.addEventListener('pointerleave', () => { set(0, 0); });
  }
})();
