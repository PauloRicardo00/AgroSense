(() => {
  'use strict';

  let current = null;
  let checking = null;
  const profiles = { ADMIN: 'admin', GESTOR: 'gestor', TECNICO: 'tecnico', OPERADOR: 'operador' };
  const messages = {
    CREDENCIAIS_INVALIDAS: 'E-mail ou senha incorretos.',
    LIMITE_TENTATIVAS: 'Muitas tentativas. Aguarde um minuto antes de tentar novamente.',
    CSRF_INVALIDO: 'Não foi possível validar a solicitação. Tente novamente.',
    LOGIN_NECESSARIO: 'Sua sessão foi encerrada. Entre novamente.',
    SEM_PERMISSAO: 'Seu perfil não pode realizar esta ação. Confira se você entrou com a conta correta.',
    ALERTA_ENCERRADO: 'Este alerta já foi encerrado e não pode ser reconhecido.',
    ALERTA_NAO_ENCONTRADO: 'O alerta não foi encontrado.',
    SEM_PRIORIDADE: 'Aguarde o gestor definir a prioridade.',
    ORDEM_CONFLITO: 'Já existe uma OS ativa para esta máquina. Consulte a ordem existente antes de abrir outra.',
    ETAPA_INVALIDA: 'A ordem foi atualizada. Confira a etapa atual antes de continuar.',
    SERVICO_INDISPONIVEL: 'As ordens de serviço estão temporariamente indisponíveis. Tente atualizar a fila.',
    ORDEM_NAO_ENCONTRADA: 'A ordem não está disponível para sua conta. Atualize a fila para conferir as ordens disponíveis.',
    CADASTRO_DUPLICADO: 'Já existe um setor com este nome ou uma máquina com este código.',
    SETOR_INATIVO: 'O setor está inativo. Atualize a lista e escolha outro setor.',
    DADOS_INVALIDOS: 'Verifique os dados informados e tente novamente.',
    BACKEND_INDISPONIVEL: 'Serviço temporariamente indisponível. Tente novamente em instantes.',
    RESPOSTA_INVALIDA: 'Não foi possível interpretar a resposta do sistema. Tente novamente.',
    SEM_CONEXAO: 'O navegador está sem conexão. Confira sua rede e tente novamente.',
    RESULTADO_INCERTO: 'O sistema não confirmou se a ação foi salva. Atualize a tela e confira o resultado antes de repetir.',
    TEMPO_ESGOTADO: 'O sistema demorou para responder. Tente novamente.',
    PERFIL_INVALIDO: 'O perfil desta conta não é reconhecido.'
  };

  function error(code, status = 0) {
    return Object.assign(new Error(messages[code] || (status >= 500 ? messages.BACKEND_INDISPONIVEL : 'Não foi possível concluir a solicitação. Tente novamente.')), { code, status });
  }

  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(path, {
        ...options,
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: { Accept: 'application/json', ...options.headers },
        signal: controller.signal
      });
      let data = null;
      if (response.status !== 204) {
        const invalidCode = response.status >= 500 ? 'BACKEND_INDISPONIVEL' : 'RESPOSTA_INVALIDA';
        if (!(response.headers.get('content-type') || '').includes('application/json')) {
          throw error(invalidCode, response.status);
        }
        try { data = await response.json(); }
        catch (err) {
          if (controller.signal.aborted) throw err;
          throw error(invalidCode, response.status);
        }
      }
      if (!response.ok) throw error(data?.codigo || (response.status >= 500 ? 'BACKEND_INDISPONIVEL' : 'RESPOSTA_INVALIDA'), response.status);
      return data;
    } catch (err) {
      if (err.code && err.status !== undefined) throw err;
      const code = !navigator.onLine ? 'SEM_CONEXAO' : controller.signal.aborted ? 'TEMPO_ESGOTADO' : 'BACKEND_INDISPONIVEL';
      throw error(code);
    } finally {
      clearTimeout(timeout);
    }
  }

  async function mutate(path, body, contentType, method = 'POST') {
    for (let attempt = 0; attempt < 2; attempt++) {
      const csrf = await request('/api/auth/csrf');
      if (typeof csrf?.headerName !== 'string' || typeof csrf?.token !== 'string') throw error('RESPOSTA_INVALIDA');
      try {
        return await request(path, {
          method, body,
          headers: { [csrf.headerName]: csrf.token, ...(contentType ? { 'Content-Type': contentType } : {}) }
        });
      } catch (err) {
        if (err.code === 'CSRF_INVALIDO' && attempt === 0) continue;
        const confirmationMissing = err.status === 0 || err.status >= 500 || (err.status >= 200 && err.status < 300);
        if (confirmationMissing && path.startsWith('/api/')) throw error('RESULTADO_INCERTO', err.status);
        throw err;
      }
    }
  }

  async function refresh() {
    if (checking) return checking;
    checking = (async () => {
      try {
        const user = await request('/api/auth/me');
        if (!Number.isSafeInteger(user?.id) || typeof user.nome !== 'string' || typeof user.email !== 'string') throw error('RESPOSTA_INVALIDA');
        if (!profiles[user.perfil]) throw error('PERFIL_INVALIDO');
        current = Object.freeze({ id: user.id, nome: user.nome, email: user.email, perfil: profiles[user.perfil] });
        return current;
      } catch (err) {
        current = null;
        if (err.status === 401) return null;
        throw err;
      }
    })();
    try { return await checking; }
    finally { checking = null; }
  }

  window.AgroSenseAuth = Object.freeze({
    roleLabels: Object.freeze({admin:'Administrador', operador:'Operador de Máquina', tecnico:'Técnico de Manutenção', gestor:'Gestor'}),
    session: () => current,
    refresh,
    async get(path) {
      if (!path.startsWith('/api/')) throw error('RESPOSTA_INVALIDA');
      try { return await request(path); }
      catch (err) {
        if (err.status === 401) {
          current = null;
          document.getElementById('app')?.setAttribute('hidden', '');
          location.replace('login.html?sessao=1');
        }
        throw err;
      }
    },
    async post(path, data) {
      if (!['/api/maquinas', '/api/setores', '/api/ordens-servico'].includes(path) && !/^\/api\/alertas\/[1-9][0-9]*\/reconhecimento$/.test(path)) throw error('RESPOSTA_INVALIDA');
      try { return await mutate(path, JSON.stringify(data), 'application/json'); }
      catch (err) {
        if (err.status === 401) {
          current = null;
          document.getElementById('app')?.setAttribute('hidden', '');
          location.replace('login.html?sessao=1');
        }
        throw err;
      }
    },
    async patch(path, data) {
      if (!/^\/api\/ordens-servico\/[1-9][0-9]*\/status$/.test(path)) throw error('RESPOSTA_INVALIDA');
      try { return await mutate(path, JSON.stringify(data), 'application/json','PATCH'); }
      catch (err) { if (err.status === 401) location.replace('login.html?sessao=1'); throw err; }
    },
    async login({ email, password }) {
      current = null;
      await mutate('/login', new URLSearchParams({ email: email.trim(), password }).toString(), 'application/x-www-form-urlencoded');
      const user = await refresh();
      if (!user) throw error('LOGIN_NECESSARIO', 401);
      return user;
    },
    async logout() {
      try { await mutate('/logout'); }
      catch (err) { if (err.status !== 401) throw err; }
      current = null;
      location.replace('login.html?saiu=1');
    }
  });
})();
