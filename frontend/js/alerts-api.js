(() => {
  'use strict';
  const states = Object.freeze({ ABERTO: 'Aberto', RECONHECIDO: 'Reconhecido', ENCERRADO: 'Encerrado' });
  const validDate = value => typeof value === 'string' && Number.isFinite(Date.parse(value));
  const optionalDate = value => value === null || validDate(value);
  const invalid = () => new Error('A resposta dos alertas está incompleta. Tente atualizar novamente.');

  function validate(alert) {
    if (!alert || !Number.isSafeInteger(alert.id) || alert.id <= 0 || typeof alert.maquinaId !== 'string'
        || !Object.hasOwn(states, alert.status) || !validDate(alert.abertoEm) || !validDate(alert.ultimaOcorrenciaEm)
        || !optionalDate(alert.encerradoEm) || !optionalDate(alert.reconhecidoEm)
        || !(alert.reconhecidoPor === null || Number.isSafeInteger(alert.reconhecidoPor))
        || !(alert.observacao === null || typeof alert.observacao === 'string')
        || !Number.isFinite(alert.scoreMaximo) || alert.scoreMaximo < 0 || alert.scoreMaximo > 1) throw invalid();
    return alert;
  }

  function query({ page = 0, size = 20, machine = '', status = '' } = {}) {
    const params = new URLSearchParams({ pagina: String(page), tamanho: String(size) });
    if (machine) params.set('maquinaId', machine);
    if (status) params.set('status', status);
    return '/api/alertas?' + params;
  }

  async function list(options = {}) {
    const result = await AgroSenseAuth.get(query(options));
    if (!Array.isArray(result?.itens) || !Number.isSafeInteger(result.total) || result.total < 0
        || result.pagina !== (options.page || 0) || result.tamanho !== (options.size || 20)
        || result.itens.length > result.tamanho || result.itens.length > result.total) throw invalid();
    result.itens.forEach(validate);
    return result;
  }

  window.AgroAlertsApi = Object.freeze({
    states, list,
    canRecognize: profile => ['admin', 'gestor', 'tecnico'].includes(profile),
    async summary(machine = '') {
      const keys = Object.keys(states);
      const totals = await Promise.all(keys.map(status => list({ machine, status, size: 1 })));
      return Object.fromEntries(keys.map((key, index) => [key, totals[index].total]));
    },
    async detail(id) {
      const alert = validate(await AgroSenseAuth.get('/api/alertas/' + id));
      if (alert.id !== id) throw invalid();
      return alert;
    },
    async recognize(id, observation) {
      const clean = observation.trim();
      if (clean.length > 1000) throw new Error('A observação deve ter no máximo 1.000 caracteres.');
      const alert = validate(await AgroSenseAuth.post('/api/alertas/' + id + '/reconhecimento', { observacao: clean || null }));
      if (alert.id !== id || alert.status === 'ABERTO') throw invalid();
      return alert;
    }
  });
})();
