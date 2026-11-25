(() => {
  'use strict';
  const states = Object.freeze({ AGUARDANDO_AUTORIZACAO: 'Aguardando autorização', AUTORIZADA: 'Autorizada',
    AGUARDANDO_ENCERRAMENTO: 'Aguardando encerramento', RECUSADA: 'Recusada', EM_ANDAMENTO: 'Em andamento', CONCLUIDA: 'Concluída', CANCELADA: 'Cancelada' });
  const priorities = Object.freeze({ BAIXA: 'Baixa', MEDIA: 'Média', ALTA: 'Alta', CRITICA: 'Crítica (SOS)' });
  const modes = Object.freeze({ MANUAL: 'Manual', AUTOMATICA: 'Automática' });
  const positive = v => Number.isSafeInteger(v) && v > 0;
  const optionalId = v => v === null || positive(v);
  const date = v => typeof v === 'string' && Number.isFinite(Date.parse(v));
  const optionalDate = v => v === null || date(v);
  const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
  const optionalText = v => v === null || typeof v === 'string';
  const invalid = () => new Error('Os dados da ordem de serviço estão incompletos. Tente atualizar novamente.');
  function validate(o) {
    if (!o || !positive(o.id) || !optionalId(o.alertaId) || !positive(o.setorId) || !positive(o.criadoPor)
        || !['numero', 'maquinaId', 'maquinaNome', 'setorNome', 'titulo'].every(k => typeof o[k] === 'string' && o[k].length)
        || !Object.hasOwn(states, o.status) || (o.prioridade !== null && !Object.hasOwn(priorities, o.prioridade)) || (o.modoAtribuicao !== null && !Object.hasOwn(modes, o.modoAtribuicao))
        || !date(o.criadaEm) || !date(o.atualizadaEm) || !object(o.dadosAbertura)
        || !['prazoPrevisto', 'atribuidaEm', 'iniciadaEm', 'concluidaEm', 'canceladaEm'].every(k => optionalDate(o[k]))
        || !optionalId(o.tecnicoId) || !optionalId(o.atribuidoPor)
        || !['descricao', 'tecnicoNome', 'relatoFinal'].every(k => optionalText(o[k]))) throw invalid();
    return o;
  }
  function validateEvent(e) {
    if (!e || !positive(e.id) || !positive(e.usuarioId) || typeof e.usuarioNome !== 'string'
        || typeof e.evento !== 'string' || !e.evento || !date(e.ocorridoEm) || !object(e.dados)) throw invalid();
    return e;
  }
  function pageParams(page, size) {
    if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 100) throw invalid();
    return new URLSearchParams({ pagina: String(page), tamanho: String(size) });
  }
  function validatePage(result, page, size, validator) {
    if (!Array.isArray(result?.itens) || !Number.isSafeInteger(result.total) || result.total < 0
        || result.pagina !== page || result.tamanho !== size || result.itens.length > size
        || result.itens.length > result.total || new Set(result.itens.map(i => i?.id)).size !== result.itens.length) throw invalid();
    result.itens.forEach(validator);
    return result;
  }
  function route(id) {
    if (!positive(id)) throw invalid();
    return '/api/ordens-servico/' + id;
  }
  window.AgroOrdersApi = Object.freeze({
    states, priorities, modes,
    canConsult: profile => ['admin', 'gestor', 'tecnico', 'operador'].includes(profile),
    async list({ page = 0, size = 20, machine = '', status = '' } = {}) {
      const params = pageParams(page, size);
      if (machine) params.set('maquinaId', machine);
      if (status) { if (!Object.hasOwn(states, status)) throw invalid(); params.set('status', status); }
      return validatePage(await AgroSenseAuth.get('/api/ordens-servico?' + params), page, size, validate);
    },
    async detail(id) {
      const order = validate(await AgroSenseAuth.get(route(id)));
      if (order.id !== id) throw invalid();
      return order;
    },
    async history(id, page = 0) {
      const params = pageParams(page, 20);
      return validatePage(await AgroSenseAuth.get(route(id) + '/historico?' + params), page, 20, validateEvent);
    },
    opening(order) {
      const raw = order.dadosAbertura.leitura;
      const machine = order.dadosAbertura.maquina;
      if (!object(raw) || !positive(raw.id) || raw.machine_id !== order.maquinaId) return null;
      return { id: raw.id, machineName: object(machine) && machine.id === order.maquinaId ? machine.nome : null,
        recebidaEm: raw.recebida_em, medidaEm: raw.medida_em,
        airTemperature: raw.air_temperature, processTemperature: raw.process_temperature,
        rotationalSpeed: raw.rotational_speed, torque: raw.torque, toolWear: raw.tool_wear,
        statusAnalise: raw.status_analise, falha: raw.falha, score: raw.score_falha, versaoModelo: raw.versao_modelo };
    },
    eventLabel: event => ({ CRIACAO: 'Criação', ATRIBUICAO: 'Atribuição', STATUS: 'Mudança de estado', AUTORIZACAO: 'Atendimento autorizado', RECUSA: 'Solicitação recusada',
      INICIO: 'Atendimento assumido', PARECER: 'Parecer técnico registrado', SOLICITACAO_ENCERRAMENTO:'Encerramento solicitado', PRIORIDADE:'Definição de prioridade', REAVALIACAO:'Reavaliação solicitada', DEVOLUCAO: 'Devolvida para ajuste', CONCLUSAO: 'Encerramento aprovado' })[event] || event,
    eventRows(event) {
      const d = event.dados, rows = [];
      const stateLabel = key => states[key] || ({ ABERTA:'Aberta', AGUARDANDO_ATRIBUICAO:'Aguardando atribuição' })[key] || key;
      const person = id => positive(id) ? 'Usuário ID ' + id : 'Sem atribuição';
      if (event.evento === 'CRIACAO' && positive(d.alerta_id)) rows.push(['Alerta vinculado', '#' + d.alerta_id]);
      if (event.evento === 'ATRIBUICAO') {
        rows.push(['Modo', modes[d.modo] || d.modo || 'Não informado'], ['Técnico de Manutenção anterior', person(d.tecnico_anterior)], ['Técnico de Manutenção atribuído', person(d.tecnico_atual)]);
      }
      if (d.anterior && d.atual && d.anterior !== d.atual) {
        rows.push(['Estado anterior', stateLabel(d.anterior) || 'Não informado'], ['Novo estado', stateLabel(d.atual) || 'Não informado']);
        if (typeof d.relato === 'string' && d.relato) rows.push(['Relato', d.relato]);
      }
      if (d.prioridadeAnterior) rows.push(['Prioridade anterior',priorities[d.prioridadeAnterior] || 'Sem definição']);
      if (d.prioridadeNova) rows.push(['Prioridade definida',priorities[d.prioridadeNova]]);
      for (const [key, label] of Object.entries({motivo:'Justificativa', causa:'Causa', reparos:'Reparos e ajustes', pecas:'Peças utilizadas', diagnostico:'Diagnóstico', servico:'Serviço realizado', verificacao:'Teste de verificação'})) {
        if (d[key]) rows.push([label, d[key]]);
      }
      return rows;
    }
  });
})();
