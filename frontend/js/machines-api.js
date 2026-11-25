(() => {
  'use strict';
  const statuses = Object.freeze({
    ATUALIZADA: { label: 'Atualizada', className: 'received', description: 'Último recebimento há menos de 30 segundos.' },
    SEM_ATUALIZACAO: { label: 'Sem atualização', className: 'delayed', description: 'Nenhuma leitura recebida nos últimos 30 segundos.' },
    SEM_LEITURAS: { label: 'Sem leituras', className: 'unread', description: 'Máquina e setor ativos, sem leituras registradas.' },
    INATIVA: { label: 'Inativa', className: 'inactive', description: 'A máquina ou seu setor está inativo.' }
  });
  const invalid = () => new Error('Os dados recebidos estão incompletos. Tente atualizar novamente.');
  const validTime = value => typeof value === 'string' && Number.isFinite(Date.parse(value));
  const nullableTime = value => value === null || validTime(value);

  function validateMachine(m) {
    if (!m || typeof m.id !== 'string' || !m.id || typeof m.nome !== 'string' || !Number.isSafeInteger(m.setorId)
        || typeof m.setorNome !== 'string' || typeof m.ativa !== 'boolean' || typeof m.setorAtivo !== 'boolean'
        || !Object.hasOwn(statuses, m.statusAtualizacao) || !validTime(m.verificadoEm)
        || !nullableTime(m.ultimaLeituraRecebidaEm)
        || !(m.segundosSemAtualizacao === null || (Number.isSafeInteger(m.segundosSemAtualizacao) && m.segundosSemAtualizacao >= 0))) throw invalid();
    return m;
  }

  function validateSector(s) {
    if (!s || !Number.isSafeInteger(s.id) || typeof s.nome !== 'string' || typeof s.ativo !== 'boolean') throw invalid();
    return s;
  }

  async function allPages(route, validate) {
    const items = new Map();
    let expected = null;
    for (let page = 0; page < 1000; page++) {
      const result = await AgroSenseAuth.get(`${route}?pagina=${page}&tamanho=100`);
      if (!Array.isArray(result?.itens) || result.pagina !== page || result.tamanho !== 100
          || !Number.isSafeInteger(result.total) || result.total < 0 || result.itens.length > 100) throw invalid();
      if (expected === null) expected = result.total;
      if (result.total !== expected) throw new Error('O cadastro mudou durante a consulta. Tente atualizar novamente.');
      for (const item of result.itens) {
        validate(item);
        if (items.has(item.id)) throw invalid();
        items.set(item.id, item);
      }
      if (items.size === expected) return [...items.values()];
      if (items.size > expected || result.itens.length !== 100) throw invalid();
    }
    throw new Error('A listagem excedeu o limite desta interface. Refine a integração antes de carregar esse volume.');
  }

  function filterMachines(items, { search = '', sector = '', status = '', sort = 'id' } = {}) {
    const normalized = value => String(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR');
    const query = normalized(search.trim());
    const byId = (a, b) => a.id.localeCompare(b.id, 'pt-BR', { numeric: true });
    return items.filter(m => (!sector || String(m.setorId) === String(sector)) && (!status || m.statusAtualizacao === status)
      && (!query || normalized([m.id, m.nome, m.setorNome].join(' ')).includes(query)))
      .sort((a, b) => (sort === 'sector' ? a.setorNome.localeCompare(b.setorNome, 'pt-BR') : sort === 'name' ? a.nome.localeCompare(b.nome, 'pt-BR') : 0) || byId(a, b));
  }

  function date(value) {
    return validTime(value) ? new Date(value).toLocaleString('pt-BR') : 'Não informado';
  }

  function readingRows(reading) {
    const format = (value, digits = 1) => Number.isFinite(value) ? value.toLocaleString('pt-BR', { minimumFractionDigits: digits, maximumFractionDigits: digits }) : 'Não disponível';
    return [
      { key:'ar_c', label: 'Temperatura do ar', value: format(Number.isFinite(reading.airTemperature) ? reading.airTemperature - 273.15 : null), unit: '°C' },
      { key:'processo_c', label: 'Temperatura do processo', value: format(Number.isFinite(reading.processTemperature) ? reading.processTemperature - 273.15 : null), unit: '°C' },
      { key:'rpm', label: 'Rotação', value: format(reading.rotationalSpeed, 0), unit: 'rpm' },
      { key:'torque', label: 'Torque', value: format(reading.torque), unit: 'N·m' },
      { key:'desgaste', label: 'Desgaste da ferramenta', value: format(reading.toolWear, 0), unit: 'min' }
    ];
  }

  function analysis(reading) {
    if (!reading) return 'Sem análise disponível';
    if (reading.statusAnalise === 'PENDENTE') return 'Análise pendente';
    if (reading.statusAnalise === 'INDISPONIVEL') return 'Análise indisponível';
    if (reading.statusAnalise === 'OK' && typeof reading.falha === 'boolean') {
      return reading.falha ? 'Indicação de falha nesta leitura' : 'Sem indicação de falha nesta leitura';
    }
    return 'Resultado de análise não informado';
  }

  window.AgroMachinesApi = Object.freeze({
    statuses, filterMachines, date, readingRows, analysis,
    async snapshot() {
      const [machines, sectors] = await Promise.all([allPages('/api/maquinas', validateMachine), allPages('/api/setores', validateSector)]);
      return { machines, sectors };
    },
    async detail(id) {
      const route = '/api/maquinas/' + encodeURIComponent(id);
      const machine = validateMachine(await AgroSenseAuth.get(route));
      let reading = null;
      try {
        reading = await AgroSenseAuth.get(route + '/ultima');
        if (!reading || reading.machineId !== id || !validTime(reading.recebidaEm)) throw invalid();
      } catch (err) {
        if (err.status !== 404) throw err;
      }
      return { machine, reading };
    }
  });
})();
