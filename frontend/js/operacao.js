/* Dados recebidos do backend para os componentes existentes. */
const AgroOperacao = (() => {
  const samplesPerMachine = 50;
  let sampleHistory = new Map();

  async function pages(read) {
    const rows = [];
    for (let page = 0; ; page++) {
      const result = await read(page);
      rows.push(...result.itens);
      if (rows.length >= result.total) return rows;
      if (!result.itens.length) throw new Error('A listagem mudou. Atualize novamente.');
    }
  }

  function assessment(alert) {
    const levels = { temp: 0, rpm: 0, pressao: 0 };
    if (!alert) return { status: 'ok', label: 'Sem alerta ativo', issues: [], levels };
    const open = alert.status === 'ABERTO';
    const issue = { key: String(alert.id), level: open ? 2 : 1, part: 'Alerta #' + alert.id,
      text: AgroAlertsApi.states[alert.status], alert };
    return { status: open ? 'crit' : 'warn', label: issue.text, issues: [issue], levels };
  }

  const sensorNames = {rpm:'Rotação (rpm)', torque:'Torque (N·m)', ar_c:'Temperatura do ar (°C)',
    processo_c:'Temperatura do processo (°C)', desgaste:'Desgaste da ferramenta (min)'};
  const rangeLabels = {DENTRO:'Dentro dos limites operacionais', ATENCAO:'Atenção', CRITICO:'Crítico',
    SEM_COMUNICACAO:'Sem comunicação', SEM_FAIXA:'Sem faixa configurada', INCOMPLETA:'Leitura incompleta', INATIVA:'Cadastro inativo'};

  function machineCondition(machine, reading) {
    if (machine.statusAtualizacao !== 'ATUALIZADA' || !reading) {
      return {status:'unknown', label:AgroMachinesApi.statuses[machine.statusAtualizacao].label};
    }
    if (machine.situacaoFaixas === 'CRITICO' || (reading.statusAnalise === 'OK' && reading.falha === true)) {
      return {status:'crit', label:machine.situacaoFaixas === 'CRITICO' ? 'Faixa crítica' : 'Falha indicada pelo modelo'};
    }
    if (machine.situacaoFaixas === 'ATENCAO') return {status:'warn', label:'Atenção na faixa operacional'};
    if (machine.situacaoFaixas === 'DENTRO' || (reading.statusAnalise === 'OK' && reading.falha === false)) {
      return {status:'ok', label:'Sem desvio detectado'};
    }
    return {status:'unknown', label:rangeLabels[machine.situacaoFaixas] || 'Sem avaliação disponível'};
  }
  function describeRanges(ranges) {
    return (ranges || []).map(f => {
      const limits = [f.minimo != null ? 'mín. ' + f.minimo : '', f.maximo != null ? 'máx. ' + f.maximo : ''].filter(Boolean);
      if (f.criticoMin != null) limits.push('crítico ≤ ' + f.criticoMin);
      if (f.criticoMax != null) limits.push('crítico ≥ ' + f.criticoMax);
      const state = ['dentro da faixa','atenção','crítico'][f.nivel] || 'sem valor válido';
      return sensorNames[f.sensor] + ' · ' + limits.join(', ') + ' · ' + state + ' na leitura · ' + f.origem;
    });
  }

  function machineCategory(machine, stored) {
    const supported = ['colheitadeira', 'trator'];
    for (const value of [machine.categoria, stored]) {
      if (value && value !== 'outras') return supported.includes(value) ? value : 'outras';
    }
    const name = (machine.nome || '').trim().toLowerCase();
    const match = /^(colheitadeira|trator)\b/.exec(name);
    if (match) return match[1];
    return 'outras';
  }

  async function snapshot(local) {
    const [catalog, alerts, recent] = await Promise.all([
      AgroMachinesApi.snapshot(), pages(page => AgroAlertsApi.list({ page, size: 100 })),
      AgroSenseAuth.get('/api/leituras')
    ]);
    const readings = new Map(recent.map(r => [r.id, r]));
    const latest = new Map();
    for (const r of recent) if (!latest.has(r.machineId)) latest.set(r.machineId, r);
    const missing = catalog.machines.filter(m => m.ultimaLeituraRecebidaEm &&
      Date.parse(latest.get(m.id)?.recebidaEm) !== Date.parse(m.ultimaLeituraRecebidaEm));
    for (let i = 0; i < missing.length; i += 6) {
      await Promise.all(missing.slice(i, i + 6).map(async m => {
        const r = await AgroSenseAuth.get('/api/maquinas/' + encodeURIComponent(m.id) + '/ultima');
        latest.set(m.id, r);
        readings.set(r.id, r);
      }));
    }
    const grouped = new Map(catalog.machines.map(m => [m.id, new Map()]));
    for (const [id, samples] of sampleHistory) {
      const machineSamples = grouped.get(id);
      if (machineSamples) for (const sample of samples) machineSamples.set(sample.id, sample);
    }
    for (const sample of readings.values()) grouped.get(sample.machineId)?.set(sample.id, sample);
    sampleHistory = new Map([...grouped].map(([id, samples]) => [
      id, [...samples.values()]
        .sort((a, b) => Date.parse(a.recebidaEm) - Date.parse(b.recebidaEm) || a.id - b.id)
        .slice(-samplesPerMachine)
    ]));
    const machines = catalog.machines.map(m => {
      const meta = local.find(x => x.id === m.id) || {};
      const rows = sampleHistory.get(m.id);
      const r = rows.at(-1);
      const history = {
        temp: rows.map(x => Number.isFinite(x.processTemperature) ? x.processTemperature - 273.15 : null),
        rpm: rows.map(x => Number.isFinite(x.rotationalSpeed) ? x.rotationalSpeed : null),
        pressao: rows.map(x => Number.isFinite(x.torque) ? x.torque : null)
      };
      const summary = rows.length ? { n: rows.length, failures:rows.filter(x => x.falha === true).length } : null;
      if (summary) for (const [key, values] of Object.entries(history)) {
        const valid = values.filter(Number.isFinite);
        if (valid.length) summary[key] = { min:Math.min(...valid), max:Math.max(...valid), avg:valid.reduce((a,b) => a+b,0)/valid.length };
      }
      return { ...meta, id:m.id, name:m.nome, setor:m.setorNome, category:machineCategory(m, meta.category),
        modelo:m.modelo || meta.modelo || 'Não informado', temp:history.temp.at(-1), rpm:history.rpm.at(-1),
        pressao:history.pressao.at(-1), history, times:rows.map(x => Date.parse(x.recebidaEm)), summary, reading:r,
        ranges:m.faixas || [], rangeState:m.situacaoFaixas, rangeLabel:rangeLabels[m.situacaoFaixas], rangeDetails:describeRanges(m.faixas),
        reception:AgroMachinesApi.statuses[m.statusAtualizacao].label,
        condition:machineCondition(m, r),
        evaluation:assessment(alerts.find(a => a.maquinaId === m.id && a.status !== 'ENCERRADO')) };
    });
    const map = new Map(machines.map(m => [m.id,m]));
    const history = [...sampleHistory.values()].flat().sort((a,b) => Date.parse(b.recebidaEm)-Date.parse(a.recebidaEm)).map(r => ({
      id:r.machineId, name:map.get(r.machineId).name, sector:map.get(r.machineId).setor, time:Date.parse(r.recebidaEm),
      temp:Number.isFinite(r.processTemperature) ? r.processTemperature-273.15 : null, rpm:r.rotationalSpeed, pressao:r.torque,
      status:r.falha === true ? 'crit' : r.falha === false ? 'ok' : 'warn', label:AgroMachinesApi.analysis(r)
    }));
    return { catalog, alerts, machines, history };
  }

  async function orders() {
    const rows = await pages(page => AgroOrdersApi.list({ page, size:100 }));
    return rows.map(o => {
      const abertura = o.dadosAbertura;
      const parecer = o.parecer || {};
      return { id:o.numero, apiId:o.id, machineId:o.maquinaId, machineName:o.maquinaNome, sector:o.setorNome,
        status:AgroOrdersApi.states[o.status], version:o.atualizadaEm, description:o.descricao || o.titulo,
        createdAt:o.criadaEm, createdBy:o.criadorNome || 'Usuário ' + o.criadoPor,
        system:abertura.sistema, impact:abertura.impacto,
        symptoms:abertura.sintomas, priority:o.prioridadePor ? o.prioridade : '', historicalPriority:o.prioridadePor ? null : AgroOrdersApi.priorities[o.prioridade],
        warning:o.agravamento, openingRanges:describeRanges(abertura.faixas), openingReading:AgroOrdersApi.opening(o),
        cause:parecer.causa, repairs:parecer.reparos, parts:parecer.pecas,
        condition:abertura.classificacao || 'Classificação não registrada na abertura',
        issues:abertura.irregularidades || (o.alertaId ? 'Alerta #' + o.alertaId : ''),
        technician:['AGUARDANDO_AUTORIZACAO','RECUSADA'].includes(o.status) ? null : o.tecnicoNome, technicianId:o.tecnicoId, managerNote:o.parecerGestor,
        diagnosis:parecer.diagnostico, resolution:parecer.servico || o.relatoFinal, validation:parecer.verificacao,
        history:null, historyOpen:false };
    });
  }
  return { snapshot, orders, pages, rangeLabels };
})();
