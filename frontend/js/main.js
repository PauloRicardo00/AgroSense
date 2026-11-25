(async () => {
const { createApp } = Vue;
const loggedUser = await AgroSenseAuth.refresh();
if (!loggedUser) { location.replace('login.html'); return; }
const userLabel = AgroSenseAuth.roleLabels;
const storedPreferences = window.AgroPreferences.load(loggedUser?.id || loggedUser?.perfil);
const visibleMenus = {
  admin: ['Dashboard', 'Máquinas', 'Cadastro', 'Ordens de serviço', 'Relatórios'],
  operador: ['Dashboard', 'Máquinas', 'Ordens de serviço', 'Relatórios'],
  tecnico: ['Dashboard', 'Máquinas', 'Ordens de serviço', 'Relatórios'],
  gestor: ['Dashboard', 'Máquinas', 'Cadastro', 'Ordens de serviço', 'Relatórios']
};

// O visualizador 3D fica fora do estado do Vue para não virar objeto reativo.
let viewer = null;
let timer = null;
let toastSequence = 0;
const toastTimers = new Map();
let returnFocusAfterDialog = null;

// Microinteração curta: apenas quando um indicador de fato muda de valor.
const numberPop = {
  updated(el, binding) {
    if (binding.value === binding.oldValue || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    el.getAnimations().forEach(animation => animation.cancel());
    el.animate([{ opacity: .65, transform: 'translateY(2px)' }, { opacity: 1, transform: 'translateY(0)' }], { duration: 240, easing: 'cubic-bezier(.2,0,0,1)' });
  }
};
const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// Cadastro salvo no navegador (se existir) ou dados de exemplo.
const saved = AgroData.load();
const metadata = saved || { machines: [], sectors: [] };
const initialSectors = [], initialMachines = [];
let refreshing = null;
let slowTimer = null;
const allCategories = [...AgroData.CATEGORIES, { key:'outras', label:'Categoria não informada' }];
const CHART_META = {
  temp: { label: 'Temperatura do processo', unit: '°C', digits: 1, span: 6 },
  rpm: { label: 'Rotação', unit: 'rpm', digits: 0, span: 300 },
  pressao: { label: 'Torque', unit: 'N·m', digits: 2, span: 1 }
};
const TELEMETRY_WINDOW_MS = 80000;
const REGIONAL_SECTORS = ['Setor Norte', 'Setor Sul', 'Setor Leste', 'Setor Oeste'];

function emptyForm(category, setor) {
  return {
    category: category || 'colheitadeira',
    id: '', name: '', modelo: '',
    setor: setor || '',
    ano: '', horas: '', ultima: '', proxima: '',
    behavior: 'normal'
  };
}

const app = createApp({
  data() {
    return {
      activeMenu: 'Início',
      browserOffline: !navigator.onLine,
      slowRequest: false,
      viewerUnavailable: false,
      recognizingAlert: false,
      currentUser: loggedUser,
      menu: [
        { label: 'Início', icon: '<svg viewBox="0 0 24 24"><path d="m3 10 9-7 9 7v10H3zM9 20v-7h6v7"/></svg>' },
        { label: 'Dashboard', icon: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>' },
        { label: 'Máquinas', icon: '<svg viewBox="0 0 24 24"><circle cx="7" cy="16.5" r="3.5"/><circle cx="18" cy="17.5" r="2.2"/><path d="M3.5 13V7h6l2 5h6.5M13 7h3l1.5 5"/></svg>' },
        { label: 'Cadastro', icon: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/></svg>' },
        { label: 'Ordens de serviço', icon: '<svg viewBox="0 0 24 24"><path d="M7 3h10v18H7zM10 8h4M10 12h4M10 16h4"/></svg>' },
        { label: 'Relatórios', icon: '<svg viewBox="0 0 24 24"><path d="M7 3h7l4 4v14H7z"/><path d="M14 3v4h4M10 12h5M10 16h5"/></svg>' },
        { label: 'Configurações', icon: '<svg viewBox="0 0 24 24"><path d="M10 3h4l.6 2.3a7.3 7.3 0 0 1 1.7.9l2.2-.8 2 3.4-1.7 1.6c.1.5.2 1.1.2 1.6s-.1 1.1-.2 1.6l1.7 1.6-2 3.4-2.2-.8c-.5.4-1.1.7-1.7.9L14 21h-4l-.6-2.3a7.3 7.3 0 0 1-1.7-.9l-2.2.8-2-3.4 1.7-1.6A7.8 7.8 0 0 1 5 12c0-.5.1-1.1.2-1.6L3.5 8.8l2-3.4 2.2.8c.5-.4 1.1-.7 1.7-.9L10 3z"/><circle cx="12" cy="12" r="2.6"/></svg>' }
      ].filter(item => item.label === 'Início' || item.label === 'Configurações' || (visibleMenus[loggedUser?.perfil] || []).includes(item.label)),
      search: '',
      showSearch: false,
      notice: false,
      profileOpen: false,
      seenAlerts: {},
      toasts: [],
      confirmDialog: null,

      // Preferências persistentes do usuário: aplicar somente valores confirmados.
      preferences: { ...storedPreferences },
      settingsDraft: { ...storedPreferences },
      settingsTab: 'account',
      settingsTabs: [
        { key: 'account', label: 'Minha conta', icon: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.6"/><path d="M4.8 20c0-4 3.2-6.3 7.2-6.3s7.2 2.3 7.2 6.3"/></svg>' },
        { key: 'appearance', label: 'Aparência', icon: '<svg viewBox="0 0 24 24"><path d="M4 18.5 17.5 5a2.1 2.1 0 0 1 3 3L7 21.5H4v-3Z"/><path d="M13.5 9 17 12.5"/></svg>' },
        { key: 'notifications', label: 'Notificações', icon: '<svg viewBox="0 0 24 24"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>' },
        { key: 'view', label: 'Visualização', title: 'Preferências de visualização', icon: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16M12.5 9h5M12.5 13h5M12.5 17h3"/></svg>' }
      ],

      machines: initialMachines,
      sectors: initialSectors,

      // Máquinas
      machineSector: 'Todos',
      machineOrder: 'default',
      machineHistory: [],
      historyExpanded: false,
      historyPeriod: 'all',
      historyStatus: 'all',
      historyPage: 1,
      historyResult: { total:0, itens:[] }, historyEnd: '', historyLoading:false, historyError: '', historyRequest:0,
      reportPeriod:'day', generatingReport:false,
      machineStatus: 'all',
      priorities:AgroOrdersApi.priorities, orderStates:AgroOrdersApi.states, orderSymptoms:'',
      queueFilter:{priority:'',status:'',machineId:'',sector:'',technicianId:''},
      orders: [], orderVersion:0, orderBusy:false, ordersLoaded:false, ordersError:'', connectionError:'', lastDataRefresh:null, loggingOut:false, manualRefreshing:false, backendAlerts: [],
      orderMachine: '',
      orderDescription: '',
      orderSystem: '',
      orderImpact: '',
      reportSnapshot: {machines:[], events:[], time:Date.now(), start:Date.now()},
      machineTab: 'Todas',
      machineTabs: ['Todas', 'Colheitadeiras', 'Tratores'],
      openId: null,
      autoRotate: !reducedMotion,

      // Dashboard
      chartKey: storedPreferences.telemetryMetric,
      alertFilter: 'all',
      selectedSector: '',
      expandedAlerts: {},
      telemetryScope: storedPreferences.telemetryScope,
      telemetryOnlyAlerts: storedPreferences.telemetryOnlyAlerts,
      chartTabs: Object.keys(CHART_META).map(k => ({ key: k, label: CHART_META[k].label })),

      // Cadastro
      cadTab: 'Máquina', remoteSectors: [], remoteMachines: [], savingCadastro: false,
      categories: AgroData.CATEGORIES,
      behaviors: AgroData.BEHAVIORS,
      form: emptyForm('colheitadeira', initialSectors.length ? initialSectors[0].nome : ''),
      formError: '',
      formOk: '',
      lastAdded: null,
      sectorForm: { nome: '', descricao: '', hectares: '' },
      sectorError: '',
      sectorOk: '',

      // Relatório
      events: [],
      now: Date.now(),
      reportSetor: 'Todos',
      reportCategoria: 'Todas'
    };
  },
  computed: {
    historyQuery() {
      if (!this.historyExpanded || !this.currentMachine || !this.historyEnd) return '';
      return new URLSearchParams({maquina:this.currentMachine.id, fim:this.historyEnd, periodo:this.historyPeriod,
        situacao:this.historyStatus, pagina:this.historyPage}).toString();
    },
    historyPages() { return Math.max(1,Math.ceil(this.historyResult.total / 20)); },
    historyVisible() {
      const m = this.currentMachine;
      return this.historyResult.itens.map(r => ({
        id:m.id, name:m.name, sector:m.setor, time:Date.parse(r.recebida_em), temp:r.temp, rpm:r.rpm, pressao:r.torque,
        status:r.falha === null ? 'warn' : r.falha ? 'crit' : 'ok',
        label:r.falha === null ? 'Sem análise conclusiva' : r.falha ? 'Indicação de falha' : 'Sem indicação de falha'
      }));
    },
    latestReadings() {
      const ids = new Set();
      return this.machineHistory.filter(r => { if (ids.has(r.id) || (this.selectedSector && r.sector !== this.selectedSector)) return false; ids.add(r.id); return true; }).slice(0,3);
    },
    userProfileLabel() { return userLabel[this.currentUser.perfil] || 'Visitante'; },
    userInitials() { return this.currentUser.nome.slice(0, 2).toUpperCase(); },
    evaluations() {
      const out = {};
      this.machines.forEach(m => { out[m.id] = m.evaluation; });
      return out;
    },
    counts() {
      const c = { total: this.machines.length, ok: 0, warn: 0, crit: 0 };
      this.machines.forEach(m => { c[this.evaluations[m.id].status] += 1; });
      return c;
    },
    alertCount() {
      return this.counts.warn + this.counts.crit;
    },
    notificationRows() {
      if (!this.preferences.internalNotifications) return [];
      return this.attentionList.filter(row =>
        (row.ev.status === 'crit' && this.preferences.criticalNotices) ||
        (row.ev.status === 'warn' && this.preferences.attentionNotices)
      );
    },
    unreadAlerts() {
      return this.notificationRows.filter(row => !this.isAlertRead(row)).length;
    },
    settingsDirty() {
      return JSON.stringify(this.settingsDraft) !== JSON.stringify(this.preferences);
    },
    settingsCurrentTab() {
      return this.settingsTabs.find(tab => tab.key === this.settingsTab) || this.settingsTabs[0];
    },
    confirmTitle() {
      if (!this.confirmDialog) return '';
      if (this.confirmDialog.type === 'machine') return 'Remover máquina?';
      if (this.confirmDialog.type === 'sector') return 'Remover setor?';
      return 'Restaurar dados de exemplo?';
    },
    confirmMessage() {
      if (!this.confirmDialog) return '';
      if (this.confirmDialog.type === 'machine') return 'O cadastro da máquina ' + this.confirmDialog.value + ' será removido.';
      if (this.confirmDialog.type === 'sector') return 'O setor ' + this.confirmDialog.value + ' será removido.';
      return 'Suas máquinas e setores cadastrados serão apagados e substituídos pelos dados de exemplo.';
    },
    confirmLabel() {
      return this.confirmDialog && this.confirmDialog.type === 'restore' ? 'Restaurar' : 'Remover';
    },
    currentMachine() {
      return this.machines.find(m => m.id === this.openId) || null;
    },
    pageSubtitle() {
      const c = this.counts;
      if (this.activeMenu === 'Dashboard') {
        return c.total + ' máquinas em ' + this.sectors.length + ' setores, ' + this.alertCount + ' com alerta';
      }
      if (this.activeMenu === 'Máquinas') return c.total + ' máquinas, ' + this.alertCount + ' com alerta';
      if (this.activeMenu === 'Cadastro') return 'Adicione máquinas e setores';
      if (this.activeMenu === 'Configurações') return 'Gerencie sua conta e personalize sua experiência.';
      if (this.activeMenu === 'Ordens de serviço') return '';
      return 'Consulte e imprima as leituras registradas';
    },
    // Dashboard
    attentionList() {
      return this.machines
        .map(m => ({ m: m, ev: this.evaluations[m.id] }))
        .filter(r => r.ev.status !== 'ok')
        .sort((a, b) => (b.ev.status === 'crit') - (a.ev.status === 'crit') || a.m.id.localeCompare(b.m.id));
    },
    // Acessos rápidos da Início: só destinos que existem e que o perfil pode abrir.
    homeShortcuts() {
      const can = label => this.menu.some(item => item.label === label);
      const svg = body => '<svg viewBox="0 0 36 36" aria-hidden="true">' + body + '</svg>';
      const all = [
        { key: 'maquinas', menu: 'Máquinas', label: 'Máquinas', text: 'Equipamentos e condições',
          icon: svg('<circle cx="11" cy="24" r="7"/><circle cx="11" cy="24" r="2.4"/><circle cx="29" cy="26.5" r="4.5"/><path d="M4.5 21V10h9.5l3.5 8H29l2.5 4.5M17.5 18v-8M14 10V6h8"/>') },
        { key: 'alertas', menu: 'Dashboard', target: '.alerts-panel', label: 'Alertas', text: 'Ocorrências ativas',
          icon: svg('<path d="M18 5.5 32 29.5H4z"/><path d="M18 14.5v8M18 26v.5"/>') },
        { key: 'relatorios', menu: 'Relatórios', label: 'Relatórios', text: 'Histórico e análises',
          icon: svg('<path d="M5.5 30V21h5v9M15.5 30V6h5v24M25.5 30V14h5v16"/>') },
        { key: 'cadastro', menu: 'Cadastro', label: 'Cadastro', text: 'Máquinas e setores',
          icon: svg('<rect x="6" y="5" width="24" height="26" rx="3"/><path d="M18 12v12M12 18h12"/>') },
        { key: 'telemetria', menu: 'Dashboard', target: '.telemetry-panel', label: 'Telemetria', text: 'Dados dos sensores',
          icon: svg('<path d="M3 20h7l4-11 8 19 4-8h7"/>') }
      ];
      return all.filter(item => can(item.menu)).slice(0, 4);
    },
    homeShortcutCol() {
      return this.homeShortcuts.length >= 4 ? 'col-12 col-sm-6 col-xl-3' : this.homeShortcuts.length === 3 ? 'col-12 col-sm-6 col-xl-4' : 'col-12 col-sm-6';
    },
    filteredAttention() {
      return this.alertFilter === 'all' ? this.attentionList : this.attentionList.filter(r => r.ev.status === this.alertFilter);
    },
    sectorMachines() {
      return this.machines.filter(m => m.setor === this.selectedSector)
        .sort((a, b) => a.id.localeCompare(b.id));
    },
    sectorRows() {
      const regionOrder = name => {
        const index = REGIONAL_SECTORS.indexOf(name);
        return index < 0 ? REGIONAL_SECTORS.length : index;
      };
      return this.sectors.map(s => {
        const items = this.machines.filter(m => m.setor === s.nome);
        const row = { s, total: items.length, ok: 0, warn: 0, crit: 0, unknown: 0 };
        items.forEach(m => { row[m.condition.status] += 1; });
        return row;
      }).filter(row => row.total > 0)
        .sort((a, b) => regionOrder(a.s.nome) - regionOrder(b.s.nome));
    },
    fleetSplit() {
      const n = c => this.machines.filter(m => m.category === c).length;
      return [
        { label: 'Tratores', value: n('trator') },
        { label: 'Colheitadeiras', value: n('colheitadeira') },
        { label: 'Setores', value: this.sectors.length }
      ];
    },
    okShare() { return this.counts.total ? Math.round((this.counts.ok / this.counts.total) * 100) : 0; },
    issueTotals() {
      const t = { warn: 0, crit: 0 };
      this.machines.forEach(m => this.evaluations[m.id].issues.forEach(i => { if (i.level === 2) t.crit += 1; else t.warn += 1; }));
      return t;
    },
    alertRows() {
      return this.filteredAttention.map(r => ({
        m: r.m, ev: r.ev,
        views: r.ev.issues.slice().sort((a, b) => b.level - a.level).map(i => this.issueView(r.m, i))
      }));
    },
    telemetryScopes() {
      const cats = AgroData.CATEGORIES.filter(c => this.machines.some(m => m.category === c.key))
        .map(c => ({ value: 'cat:' + c.key, label: c.label + ' (' + this.machines.filter(m => m.category === c.key).length + ')' }));
      const ms = this.machines.map(m => ({ value: 'm:' + m.id, label: m.id + ' · ' + m.name + ' · ' + m.setor }));
      return [{ value: 'all', label: 'Toda a frota (' + this.machines.length + ')' }].concat(cats, ms);
    },
    telemetry() {
      const key = this.chartKey, meta = CHART_META[key], sc = this.telemetryScope;
      let list = this.machines;
      if (sc.indexOf('cat:') === 0) list = list.filter(m => m.category === sc.slice(4));
      else if (sc.indexOf('m:') === 0) list = list.filter(m => m.id === sc.slice(2));
      const level = m => ({ crit:2, warn:1, ok:0 })[m.evaluation.status];
      const scopeTotal = list.length;
      const outCount = list.filter(m => level(m) > 0).length;
      if (this.telemetryOnlyAlerts) list = list.filter(m => level(m) > 0);
      const sensor = {temp:'processo_c', rpm:'rpm', pressao:'torque'}[key];
      const available = [];
      let latestTime = null;
      for (const m of list) {
        const values = m.history?.[key];
        const last = values?.findLastIndex((value, i) => Number.isFinite(value) && Number.isFinite(m.times[i]));
        if (last == null || last < 0) continue;
        const time = m.times[last];
        latestTime = latestTime === null ? time : Math.max(latestTime, time);
        const range = m.ranges.find(f => f.sensor === sensor);
        const current = ['DENTRO','ATENCAO','CRITICO'].includes(m.rangeState);
        const sensorSeverity = current ? range?.nivel : null;
        const severity = sensorSeverity ?? ({ok:0,warn:1,crit:2})[m.condition.status];
        const label = ['Dentro dos limites','Atenção','Crítico'][sensorSeverity] || 'Equipamento: ' + m.condition.label;
        available.push({id:m.id, name:m.name, level:severity ?? 3, label, range, values, times:m.times, now:values[last], time});
      }
      const start = latestTime - TELEMETRY_WINDOW_MS;
      const series = available.filter(s => s.time >= start).map(s => {
        const first = s.times.findIndex(time => Number.isFinite(time) && time >= start);
        return { ...s, times:s.times.slice(first), values:s.values.slice(first) };
      });

      const limits = [];
      const range = series.length === 1 && series[0].range;
      if (range) {
        for (const [field,label,level] of [['minimo','Mín.',1],['maximo','Máx.',1],['criticoMin','Crít. mín.',2],['criticoMax','Crít. máx.',2]]) {
          if (range[field] != null) limits.push({v:range[field],label,level});
        }
      }
      let emptyText = '';
      if (!series.length) {
        if (!scopeTotal) emptyText = 'Nenhuma máquina neste escopo.';
        else if (this.telemetryOnlyAlerts && !outCount) emptyText = 'Nenhuma máquina com alerta ativo neste filtro.';
        else if (!list.length) emptyText = 'Nenhuma máquina corresponde aos filtros.';
        else emptyText = 'Histórico insuficiente: aguarde as próximas leituras.';
      }
      return { meta, series, available, limits, scopeTotal, outCount, emptyText, latestTime,
        delayed: latestTime !== null && this.now - latestTime >= 30000 };
    },
    chart() {
      return AgroData.fleetChart(this.machines, this.chartKey, CHART_META[this.chartKey].span);
    },

    // Máquinas
    groupedMachines() {
      const term = this.search.trim().toLowerCase();
      return allCategories
        .filter(c => this.machineTab === 'Todas' || this.machineTab === c.label)
        .map(c => ({
          key: c.key,
          label: c.label,
          items: this.machines.filter(m => m.category === c.key &&
            (this.machineSector === 'Todos' || m.setor === this.machineSector) &&
            (this.machineStatus === 'all' || this.evaluations[m.id].status === this.machineStatus) &&
            (!term || [m.id, m.name, m.modelo, m.setor].join(' ').toLowerCase().includes(term)))
            .sort((a, b) => this.compareMachines(a, b))
        }))
        .filter(g => g.items.length);
    },

    // Cadastro
    registeredGroups() {
      const local = new Map(this.machines.map(m => [m.id, m]));
      const rows = this.remoteMachines.map(r => ({
        id: r.id, name: r.nome, setor: r.setorNome, category: local.get(r.id)?.category,
        label: ({ ATUALIZADA: 'Atualizada', SEM_ATUALIZACAO: 'Sem atualização', SEM_LEITURAS: 'Sem leituras', INATIVA: 'Inativa' })[r.statusAtualizacao]
      }));
      const groups = this.categories.map(c => ({ ...c, machines: rows.filter(m => m.category === c.key) }));
      const others = rows.filter(m => !m.category || m.category === 'outras');
      if (others.length) groups.push({ key: 'outras', label: 'Máquinas sem categoria informada', machines: others });
      return groups;
    },
    homeDescription() {
      return { operador:'Acompanhe as condições dos equipamentos, identifique irregularidades e solicite manutenções quando necessário.',
        tecnico:'Acompanhe as ordens de serviço, consulte as prioridades e gerencie suas atividades de manutenção.',
        gestor:'Monitore as operações, organize as prioridades de manutenção e antecipe paradas críticas.',
        admin:'Acompanhe equipamentos, alertas e manutenções.' }[this.currentUser.perfil];
    },
    machinesById() { return Object.fromEntries(this.machines.map(m => [m.id,m])); },
    visibleOrders() {
      const filtered = this.orders.filter(order =>
        Object.entries(this.queueFilter).every(([field, value]) => !value || order[field] === value));
      if (this.currentUser.perfil !== 'gestor') return filtered;

      const waiting = AgroOrdersApi.states.AGUARDANDO_AUTORIZACAO;
      return [
        ...filtered.filter(order => order.status === waiting),
        ...filtered.filter(order => order.status !== waiting)
      ];
    },
    queueOptions() {
      const unique = key => [...new Set(this.orders.map(o => o[key]).filter(Boolean))].sort();
      return {machines:unique('machineId'), sectors:unique('sector'),
        technicians:[...new Map(this.orders.filter(o=>o.technician).map(o=>[o.technicianId,o.technician])).entries()]};
    },
    activeOrdersByMachine() {
      return Object.fromEntries(this.orders.filter(o => !['Concluída', 'Cancelada', 'Recusada'].includes(o.status)).map(o => [o.machineId, o]));
    },
    suggestedId() { return AgroData.nextId([...this.remoteMachines, ...this.machines], this.form.category); },
    suggestedName() { return AgroData.DEFAULT_NAME[this.form.category]; },
    sectorCounts() {
      const out = {};
      this.sectors.forEach(s => { out[s.nome] = 0; });
      this.machines.forEach(m => { out[m.setor] = (out[m.setor] || 0) + 1; });
      return out;
    },

    // Relatório
    reportRows() {
      return (this.reportSnapshot?.machines || this.machines)
        .filter(m => (this.reportSetor === 'Todos' || m.setor === this.reportSetor) &&
                     (this.reportCategoria === 'Todas' || m.category === this.reportCategoria))
        .map(m => ({ m: m, ev: m.evaluation, sum: m.summary }));
    },
    reportCounts() {
      const c = { ok: 0, warn: 0, crit: 0 };
      this.reportRows.forEach(r => { c[r.ev.status] += 1; });
      return c;
    },
    reportReadings() {
      return this.reportRows.reduce((max, r) => Math.max(max, r.sum ? r.sum.n : 0), 0);
    },
    reportIssues() {
      const out = [];
      this.reportRows.forEach(r => r.ev.issues.forEach(i => out.push({ id: r.m.id, setor: r.m.setor, issue: i })));
      return out.sort((a, b) => b.issue.level - a.issue.level);
    },
    reportEvents() {
      const ids = this.reportRows.map(r => r.m.id);
      return (this.reportSnapshot?.events || this.events).filter(e => ids.indexOf(e.id) !== -1).slice(0, 30);
    },
    reportFilter() {
      const cat = this.reportCategoria === 'Todas' ? 'Todas'
        : (this.reportCategoria === 'trator' ? 'Tratores' : 'Colheitadeiras');
      return 'Setor: ' + this.reportSetor + '. Categoria: ' + cat + '.';
    }
  },
  watch: {
    historyExpanded(open) { if (open) this.historyEnd = new Date().toISOString(); },
    async historyQuery(query) {
      const request = ++this.historyRequest;
      this.historyResult = {total:0,itens:[]};
      this.historyError = '';
      this.historyLoading = !!query;
      if (!query) return;
      try {
        const page = await AgroSenseAuth.get('/api/leituras/historico?' + query);
        if (request === this.historyRequest) this.historyResult = page;
      } catch (err) { if (request === this.historyRequest) this.historyError = err.message; }
      finally { if (request === this.historyRequest) this.historyLoading = false; }
    },
    historyPeriod() { this.historyPage = 1; },
    historyStatus() { this.historyPage = 1; },
    activeMenu(menu) {
      this.$nextTick(() => this.animatePage());
      if (history.state?.agroMenu !== menu) {
        history.pushState({ ...history.state, agroMenu: menu }, '');
      }
      this.notice = false;
      this.profileOpen = false;
      this.showSearch = false;
      if (menu !== 'Máquinas') this.backToList();
    },

    chartKey(value) { this.rememberLiveTelemetry('telemetryMetric', value); },
    telemetryScope(value) { this.rememberLiveTelemetry('telemetryScope', value); },
    telemetryOnlyAlerts(value) { this.rememberLiveTelemetry('telemetryOnlyAlerts', value); }
  },
  mounted() {
    document.documentElement.dataset.theme = this.preferences.theme;
    this.tick();
    history.replaceState({ ...history.state, agroMenu: this.activeMenu }, '');
    window.addEventListener('offline', this.connectionChanged);
    window.addEventListener('online', this.connectionChanged);
    window.addEventListener('popstate', this.restoreMenu);
    window.addEventListener('beforeunload', this.warnUnsaved);

    timer = setInterval(() => { if (!document.hidden && !this.savingCadastro && !this.orderBusy) this.tick(); }, 10000);
    document.addEventListener('pointerdown', this.onOutsidePointer);
    document.addEventListener('keydown', this.onGlobalKeydown);
  },
  beforeUnmount() {



    window.removeEventListener('offline', this.connectionChanged);
    window.removeEventListener('online', this.connectionChanged);
    clearTimeout(slowTimer);
    window.removeEventListener('popstate', this.restoreMenu);
    window.removeEventListener('beforeunload', this.warnUnsaved);
    clearInterval(timer);
    toastTimers.forEach(timeout => clearTimeout(timeout));
    toastTimers.clear();
    document.removeEventListener('pointerdown', this.onOutsidePointer);
    document.removeEventListener('keydown', this.onGlobalKeydown);
    this.destroyViewer();
  },
  methods: {
    animatePage() {
      if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
      const targets = document.querySelectorAll('.main-panel > .topbar, .home-hero, .home-shortcut, .orders-view > .order-form, .machine-list > .toolbar, .report-controls');
      targets.forEach((el,i) => {
        el.getAnimations().forEach(a => a.cancel());
        el.animate([{opacity:.25,transform:'translateY(10px)'},{opacity:1,transform:'translateY(0)'}],{duration:360,delay:Math.min(i*35,140),easing:'cubic-bezier(.22,1,.36,1)'});
      });
    },
    viewHistory(id) {
      if (!this.goMenu('Máquinas')) return;
      this.showMachine(id);
      this.historyExpanded = true;
      this.$nextTick(() => this.$refs.machineHistoryPanel?.scrollIntoView({behavior:reducedMotion ? 'auto' : 'smooth',block:'start'}));
    },
    async logout() {
      if (this.loggingOut || !this.confirmDiscardSettings()) return;
      this.loggingOut = true;
      try { await window.AgroSenseAuth.logout(); }
      catch (err) { this.showToast('Não foi possível sair da conta. ' + err.message, 'error'); }
      finally { this.loggingOut = false; }
    },
    fmt(value, digits) { return Number.isFinite(value) ? AgroData.fmt(value,digits) : '—'; },
    sensorRows(r) { return r ? AgroMachinesApi.readingRows(r) : []; },
    readings(m) {
      if (!m.reading) return [];
      const chartKeys = {processo_c:'temp', rpm:'rpm', torque:'pressao'};
      const minimumSpans = {temp:.3, rpm:40, pressao:.2};
      return AgroMachinesApi.readingRows(m.reading).map(r => {
        const chartKey = chartKeys[r.key];
        const values = m.history[chartKey]?.filter(Number.isFinite) || [];
        const limits = m.ranges.find(item => item.sensor === r.key);
        const range = [
          limits?.minimo == null ? '' : 'mín. ' + this.fmt(limits.minimo, 1),
          limits?.maximo == null ? '' : 'máx. ' + this.fmt(limits.maximo, 1)
        ].filter(Boolean).join(' · ');
        const level = ['DENTRO','ATENCAO','CRITICO'].includes(m.rangeState) ? limits?.nivel : null;
        return { ...r, points:AgroData.spark(values, minimumSpans[chartKey]), range:range ? 'Faixa: ' + range + ' ' + r.unit : '', level:level ?? 0 };
      });
    },
    fmtDateTime(ts) { return new Date(ts).toLocaleString('pt-BR'); },
    fmtTime(ts) { return new Date(ts).toLocaleTimeString('pt-BR'); },
    duration(ms) {
      const s = Math.max(0, Math.round(ms / 1000));
      if (s < 60) return s + ' s';
      const m = Math.floor(s / 60);
      if (m < 60) return m + ' min ' + (s % 60) + ' s';
      return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
    },

    alertKey(row) {
      return 'agrosense:notice-read:v1:' + JSON.stringify([
        this.currentUser.id, row.m.id, row.ev.status, row.ev.issues.map(issue => issue.key)
      ]);
    },
    isAlertRead(row) {
      const key = this.alertKey(row);
      if (this.seenAlerts[key]) return true;
      try { return localStorage.getItem(key) === '1'; }
      catch { return false; }
    },
    markAlertsRead(rows) {
      let saved = true;
      for (const row of rows) {
        const key = this.alertKey(row);
        this.seenAlerts[key] = true;
        try { localStorage.setItem(key, '1'); }
        catch { saved = false; }
      }
      if (!saved) this.showToast('A leitura será lembrada apenas nesta sessão. O navegador não conseguiu salvá-la.', 'error');
    },
    markAllAlertsRead() {
      this.markAlertsRead(this.notificationRows);
    },
    openAlert(row) {
      this.markAlertsRead([row]);
      this.notice = false;
      this.openMachineById(row.m.id);
    },
    toggleNotice() {
      this.profileOpen = false;
      this.notice = !this.notice;
    },
    toggleProfile() {
      this.notice = false;
      this.profileOpen = !this.profileOpen;
    },
    toggleSearch() {
      this.showSearch = !this.showSearch;
      if (this.showSearch) this.$nextTick(() => this.$refs.searchInput && this.$refs.searchInput.focus());
    },
    onOutsidePointer(e) {
      if (this.notice && this.$refs.notificationArea && !this.$refs.notificationArea.contains(e.target)) this.notice = false;
      if (this.profileOpen && this.$refs.profileArea && !this.$refs.profileArea.contains(e.target)) this.profileOpen = false;
    },
    onGlobalKeydown(e) {
      if (e.key !== 'Escape') return;
      if (this.confirmDialog) { e.preventDefault(); this.dismissConfirm(); return; }
      this.notice = false;
      this.profileOpen = false;
      this.showSearch = false;
    },
    showToast(text, kind = 'success') {
      if (this.toasts.some(toast => toast.text === text && toast.kind === kind)) return;
      const id = ++toastSequence;
      this.toasts.push({ id, text, kind });
      if (this.toasts.length > 3) this.closeToast(this.toasts[0].id);
      if (kind !== 'error') toastTimers.set(id, setTimeout(() => this.closeToast(id), 4400));
    },
    closeToast(id) {
      this.toasts = this.toasts.filter(t => t.id !== id);
      if (toastTimers.has(id)) { clearTimeout(toastTimers.get(id)); toastTimers.delete(id); }
    },
    requestConfirm(type, value = '') {
      returnFocusAfterDialog = document.activeElement;
      this.notice = false;
      this.profileOpen = false;
      this.confirmDialog = { type, value };
      this.$nextTick(() => this.$refs.cancelConfirmBtn && this.$refs.cancelConfirmBtn.focus());
    },
    dismissConfirm() {
      this.confirmDialog = null;
      this.$nextTick(() => {
        if (returnFocusAfterDialog && returnFocusAfterDialog.isConnected) returnFocusAfterDialog.focus();
        returnFocusAfterDialog = null;
      });
    },
    onDialogKeydown(e) {
      if (e.key !== 'Tab') return;
      const controls = Array.from(e.currentTarget.querySelectorAll('button:not([disabled])'));
      if (!controls.length) return;
      const first = controls[0], last = controls[controls.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    },
    confirmAction() {
      if (!this.confirmDialog) return;
      const { type, value } = this.confirmDialog;
      this.dismissConfirm();
      if (type === 'machine') {
        if (!this.machines.some(m => m.id === value)) return;
        if (this.openId === value) this.backToList();
        this.machines = this.machines.filter(m => m.id !== value);
        this.events = this.events.filter(e => e.id !== value);
        if (this.lastAdded === value) { this.lastAdded = null; this.formOk = ''; }
        this.persist();
        this.showToast('Máquina ' + value + ' removida.');
      } else if (type === 'sector') {
        if (this.sectorCounts[value] > 0) return;
        this.sectors = this.sectors.filter(s => s.nome !== value);
        if (this.form.setor === value) this.form.setor = this.sectors.length ? this.sectors[0].nome : '';
        if (this.reportSetor === value) this.reportSetor = 'Todos';
        this.showToast('Setor ' + value + ' removido.');
      } else if (type === 'restore') {
        AgroData.clearSaved();
        window.location.reload();
      }
    },

    // Navegação, com proteção de alterações não salvas em Configurações.
    confirmDiscardSettings() {
      if (this.activeMenu !== 'Configurações' || !this.settingsDirty) return true;
      if (!window.confirm('Descartar alterações de configurações que ainda não foram salvas?')) return false;
      this.settingsDraft = { ...this.preferences };
      return true;
    },
    warnUnsaved(event) {
      if (this.activeMenu !== 'Configurações' || !this.settingsDirty) return;
      event.preventDefault();
      event.returnValue = '';
    },
    goMenu(label) {
      if (!this.menu.some(item => item.label === label)) return false;
      if (label !== this.activeMenu && !this.confirmDiscardSettings()) return false;
      if (label === 'Máquinas') this.backToList();
      this.activeMenu = label;
      return true;
    },
    restoreMenu(event) {
      if (!this.goMenu(event.state?.agroMenu || 'Início')) {
        history.pushState({ ...history.state, agroMenu: this.activeMenu }, '');
      }
    },
    goFromHome(label, target) {
      this.goMenu(label);
      this.$nextTick(() => {
        const section = target ? document.querySelector('.main-panel ' + target) : null;
        const focusEl = section ? section.querySelector('h2') : document.querySelector('.main-panel h1');
        focusEl?.setAttribute('tabindex', '-1');
        focusEl?.focus({ preventScroll: true });
        if (section) section.scrollIntoView({ block: 'start', behavior: reducedMotion ? 'auto' : 'smooth' });
        else window.scrollTo(0, 0);
      });
    },
    machineIcon(category) {
      return category === 'colheitadeira'
        ? '<svg viewBox="0 0 28 20" aria-hidden="true"><circle cx="9" cy="15" r="3.4"/><circle cx="22" cy="16" r="2.4"/><path d="M1 5.5h4V14M5 5.5h5.5V3H19v6h5.5v5.5M10.5 3v6.5"/></svg>'
        : '<svg viewBox="0 0 28 20" aria-hidden="true"><circle cx="8" cy="14" r="5"/><circle cx="23" cy="16" r="3"/><path d="M13 12.5h5.5M13 7.5V4h6l2.5 6.5h4V13M3 9h5"/></svg>';
    },
    issueView(m, issue) {
      return { key:issue.key, level:issue.level, metric:issue.part, value:issue.text, unit:'', cmp:'', limit:AgroData.fmt(issue.alert.scoreMaximo,4) };
    },
    pickSector(name) { this.selectedSector = this.selectedSector === name ? '' : name; },
    openMachineById(id) {
      this.activeMenu = 'Máquinas';
      this.showMachine(id);
    },

    // Configurações: o mesmo adaptador será substituído pela API quando existir backend.
    compareMachines(a, b) {
      const byId = () => a.id.localeCompare(b.id, 'pt-BR', { numeric: true });
      const sort = this.machineOrder === 'default' ? this.preferences.machineSort : this.machineOrder;
      if (sort === 'sector') {
        return a.setor.localeCompare(b.setor, 'pt-BR') || byId();
      }
      if (sort === 'severity') {
        const rank = { crit: 0, warn: 1, ok: 2 };
        return rank[this.evaluations[a.id].status] - rank[this.evaluations[b.id].status] || byId();
      }
      return byId();
    },
    rememberLiveTelemetry(field, value) {
      if (this.preferences[field] === value) return;
      const result = window.AgroPreferences.save(this.currentUser.id || this.currentUser.perfil, { ...this.preferences, [field]: value });
      if (!result.ok) {
        this.showToast('Não foi possível memorizar a preferência neste navegador.', 'error');
        return;
      }
      const wasClean = !this.settingsDirty;
      this.preferences = result.value;
      if (wasClean) this.settingsDraft = { ...result.value };
    },
    applySettings(value) {
      document.documentElement.dataset.theme = value.theme;
      this.preferences = { ...value };
      this.settingsDraft = { ...value };
      this.chartKey = value.telemetryMetric;
      this.telemetryScope = this.telemetryScopes.some(o => o.value === value.telemetryScope) ? value.telemetryScope : 'all';
      this.telemetryOnlyAlerts = value.telemetryOnlyAlerts;
      if (!value.internalNotifications) this.notice = false;
    },
    saveSettings() {
      if (!this.settingsDirty) return;
      const draft = { ...this.settingsDraft };
      if (!this.telemetryScopes.some(o => o.value === draft.telemetryScope)) draft.telemetryScope = 'all';
      const result = window.AgroPreferences.save(this.currentUser.id || this.currentUser.perfil, draft);
      if (!result.ok) { this.showToast('Não foi possível salvar as preferências no navegador.', 'error'); return; }
      this.applySettings(result.value);
      this.showToast('Preferências salvas neste navegador.');
    },
    discardSettings() {
      if (!this.settingsDirty) return;
      this.settingsDraft = { ...this.preferences };
      this.showToast('Alterações não salvas descartadas.');
    },
    resetSettings() {
      if (!window.confirm('Restaurar todas as preferências para os valores padrão?')) return;
      const result = window.AgroPreferences.restore(this.currentUser.id || this.currentUser.perfil);
      if (!result.ok) { this.showToast('Não foi possível restaurar as preferências.', 'error'); return; }
      this.applySettings(result.value);
      this.showToast('Preferências padrão restauradas.');
    },

    connectionChanged() {
      this.browserOffline = !navigator.onLine;
      if (!this.browserOffline) this.tick();
    },
    async tick() {
      if (refreshing) return refreshing;
      refreshing = this.refreshBackend().catch(err => { this.connectionError = err.message; return false; });
      slowTimer = setTimeout(() => { this.slowRequest = true; }, 4000);
      try { return await refreshing; }
      finally {
        clearTimeout(slowTimer);
        this.slowRequest = false;
        refreshing = null;
      }
    },
    async refreshFromButton() {
      const previous = this.machineHistory[0]?.time;
      this.manualRefreshing = true;
      try {
        if (await this.tick()) {
          if (this.activeMenu === 'Ordens de serviço' && this.ordersError) return;
          const latest = this.machineHistory[0]?.time;
          this.showToast(latest != null && (previous == null || latest > previous)
            ? 'Dados atualizados. Última leitura às ' + this.fmtTime(latest) + '.'
            : 'Consulta concluída. Nenhuma leitura nova desde a última atualização.', 'success');
        }
      } finally { this.manualRefreshing = false; }
    },
    async refreshBackend() {
      const user = await AgroSenseAuth.refresh();
      if (!user) { location.replace('login.html?sessao=1'); return false; }
      if (user.id !== this.currentUser.id || user.perfil !== this.currentUser.perfil) {
        location.reload();
        return false;
      }
      const data = await AgroOperacao.snapshot(metadata.machines);
      this.machines = data.machines;
      if (!this.telemetryScopes.some(option => option.value === this.telemetryScope)) this.telemetryScope = 'all';
      this.remoteMachines = data.catalog.machines;
      this.connectionError = '';
      this.lastDataRefresh = Date.now();
      this.remoteSectors = data.catalog.sectors.map(s => ({ ...s, hectares:s.hectares ?? metadata.sectors.find(x => x.nome === s.nome)?.hectares }));
      this.sectors = this.remoteSectors;
      if (!this.remoteSectors.some(s => s.ativo && s.nome === this.form.setor))
        this.form.setor = this.remoteSectors.find(s => s.ativo)?.nome || '';
      this.backendAlerts = data.alerts;
      this.machineHistory = data.history;
      this.now = Date.now();
      this.events = data.alerts.map(a => ({ id:a.maquinaId, setor:this.machines.find(m => m.id === a.maquinaId)?.setor,
        time:Date.parse(a.ultimaOcorrenciaEm), label:AgroAlertsApi.states[a.status], text:'Alerta #' + a.id }));
      if (!this.reportSnapshot.machines.length) this.captureReport();
      if (AgroOrdersApi.canConsult(this.currentUser.perfil)) await this.loadOrders();
      return true;
    },
    issuesFor(m) {
      return [];
    },
    persist() {
      AgroData.save(metadata.machines, metadata.sectors);
    },

    // Máquinas (detalhe e 3D)
    showMachine(id) {
      this.openId = id;
      this.historyExpanded = false;
      this.historyPage = 1;
      this.historyPeriod = 'all';
      this.historyStatus = 'all';
      this.$nextTick(() => this.mountViewer());
    },
    backToList() {
      this.destroyViewer();
      this.openId = null;
    },
    mountViewer() {
      this.destroyViewer();
      this.viewerUnavailable = false;
      const m = this.currentMachine;
      if (!m || m.category === 'outras' || !this.$refs.stage) return;
      try {
        viewer = new MachineViewer(this.$refs.stage, {
          type: m.category,
          onInspect: () => { this.autoRotate = false; },
          autoRotate: this.autoRotate,
          issues: this.issuesFor(m)
        });
      } catch (err) {
        console.error('Falha ao abrir o visualizador 3D:', err);
        this.viewerUnavailable = true;
      }
    },
    destroyViewer() {
      if (viewer) { viewer.destroy(); viewer = null; }
    },
    toggleRotate() {
      this.autoRotate = !this.autoRotate;
      if (viewer) viewer.setAutoRotate(this.autoRotate);
    },
    resetView() { if (viewer) viewer.resetView(); },
    focusIssue(issue) { this.autoRotate = false; if (viewer) { viewer.setAutoRotate(false); viewer.focus(issue.anchor); } },

    // Cadastro de máquinas
    async addMachine() {
      if (this.savingCadastro) return;
      this.formOk = '';
      const result = AgroData.buildConfig(this.form, [...this.remoteMachines, ...this.machines], this.remoteSectors.filter(s => s.ativo));
      if (result.error) {
        this.formError = result.error;
        return;
      }
      this.formError = '';
      const sector = this.remoteSectors.find(s => s.ativo && s.nome === this.form.setor);
      if (!sector) { this.formError = 'Selecione um setor ativo.'; return; }
      this.savingCadastro = true;
      if (refreshing) await refreshing;
      try {
        const record = await AgroSenseAuth.post('/api/maquinas', { id: result.config.id, nome: result.config.name, modelo:result.config.modelo, setorId: sector.id });
        this.remoteMachines.push(record);
      } catch (err) { this.formError = err.message; return; }
      finally { this.savingCadastro = false; }
      const m = result.config;
      metadata.machines.push(m);
      this.persist();
      await this.tick();
      this.lastAdded = m.id;
      this.formOk = 'Máquina ' + m.id + ' adicionada ao ' + m.setor + '.';
      this.form = emptyForm(this.form.category, this.form.setor);
    },
    removeMachine(id) { if (this.remoteMachines.some(m => m.id === id)) { this.showToast('A API permite inativação da máquina. A exclusão está indisponível.', 'info'); return; } this.requestConfirm('machine', id); },

    // Cadastro de setores
    async addSector() {
      if (this.savingCadastro) return;
      this.sectorOk = '';
      const nome = this.sectorForm.nome.trim();
      if (!nome) { this.sectorError = 'Informe o nome do setor.'; return; }
      if (this.remoteSectors.some(s => s.nome.toLowerCase() === nome.toLowerCase())) {
        this.sectorError = 'Já existe um setor chamado ' + nome + '.';
        return;
      }
      const ha = this.sectorForm.hectares === '' ? 0 : Number(this.sectorForm.hectares);
      if (!isFinite(ha) || ha < 0) { this.sectorError = 'A área não pode ser negativa.'; return; }
      this.sectorError = '';
      this.savingCadastro = true;
      if (refreshing) await refreshing;
      try {
        const record = await AgroSenseAuth.post('/api/setores', { nome, hectares:ha, descricao: this.sectorForm.descricao.trim() });
        this.remoteSectors.push({ ...record, hectares: ha });
        metadata.sectors.push({ ...record, hectares: ha });
        this.persist();
      } catch (err) { this.sectorError = err.message; return; }
      finally { this.savingCadastro = false; }
      if (!this.form.setor) this.form.setor = nome;
      this.sectorOk = 'Setor ' + nome + ' adicionado.';
      this.sectorForm = { nome: '', descricao: '', hectares: '' };
    },
    removeSector(nome) {
      if (this.remoteSectors.some(s => s.nome === nome)) { this.showToast('A API permite inativação do setor. A exclusão está indisponível.', 'info'); return; }
      if (this.sectorCounts[nome] > 0) return;
      this.requestConfirm('sector', nome);
    },
    restoreExamples() { this.tick(); },

    async loadOrders() {
      const orderVersion = ++this.orderVersion;
      let next;
      try { next = await AgroOperacao.orders(); }
      catch (err) {
        if (orderVersion === this.orderVersion) this.ordersError = err.message;
        return;
      }
      if (orderVersion !== this.orderVersion) return;
      this.ordersLoaded = true;
      this.ordersError = '';
      for (const row of next) {
        const prior = this.orders.find(o => o.id === row.id);
        row.historyOpen = prior?.historyOpen || false;
        if (prior?.version === row.version) {
          row.history = prior.history;
          row.historyError = prior.historyError;
          row.historyLoading = prior.historyLoading;
        }
      }
      this.orders = next;
      for (const row of this.orders.filter(o => o.historyOpen && !o.history && !o.historyError)) await this.readOrderHistory(row);
    },
    async loadOrderHistory(order, event) {
      order.historyOpen = event.target.open;
      if (order.historyOpen && !order.history) await this.readOrderHistory(order);
    },
    async readOrderHistory(order) {
      if (order.historyLoading) return;
      order.historyLoading = true;
      order.historyError = '';
      const currentOrder = () => this.orders.find(o => o.apiId === order.apiId && o.version === order.version);
      try {
        const rows = await AgroOperacao.pages(page => AgroOrdersApi.history(order.apiId, page));
        const current = currentOrder();
        if (current) current.history = rows.map(e => ({ event:AgroOrdersApi.eventLabel(e.evento),
          by:e.usuarioNome, role:userLabel[e.dados.autorPerfil?.toLowerCase()], at:e.ocorridoEm, note:AgroOrdersApi.eventRows(e).map(([k,v]) => k + ': ' + v).join(' · ') }));
      } catch (err) {
        const current = currentOrder();
        if (current) current.historyError = err.message;
      } finally {
        const current = currentOrder();
        if (current) current.historyLoading = false;
      }
    },
    viewOrder(id) { Object.keys(this.queueFilter).forEach(k => this.queueFilter[k]=''); if (this.goMenu('Ordens de serviço')) this.$nextTick(() => document.getElementById(id)?.focus()); },
    prepareOrder(id) { if (this.goMenu('Ordens de serviço')) this.orderMachine = id; },
    async createOrder() {
      if (this.orderBusy) return;
      this.orderBusy = true;
      try {
        const order = await AgroSenseAuth.post('/api/ordens-servico', { maquinaId:this.orderMachine,
          sistema:this.orderSystem, impacto:this.orderImpact, descricao:this.orderDescription, sintomas:this.orderSymptoms });
        this.orderSymptoms = this.orderDescription = this.orderSystem = this.orderImpact = this.orderMachine = '';
        this.showToast(order.numero + ' enviada ao gestor.');
        await this.loadOrders();
      } catch (err) { this.showToast(err.message,'error'); } finally { this.orderBusy=false; }
    },
    async updateOrder(order, action, form) {
      if (this.orderBusy) return;
      const initial = order.status === 'Aguardando autorização';
      const actions = { start:'assumir', submit:'enviar', approve:initial ? 'autorizar' : 'concluir',
        priority:'priorizar', escalate:'reavaliar', return:initial ? 'recusar' : 'devolver' };
      const body = { acao:actions[action], versao:order.version };
      if (form) {
        const data = new FormData(form);
        if (action === 'submit') {
          const required = ['diagnosis','cause','repairs','resolution','validation'];
          const empty = required.find(name => !String(data.get(name) || '').trim());
          if (empty) {
            this.showToast('Preencha o campo destacado para enviar o parecer.', 'error');
            form.elements[empty].focus();
            return;
          }
          body.diagnostico = data.get('diagnosis').trim();
          body.causa = data.get('cause').trim();
          body.reparos = data.get('repairs').trim();
          body.pecas = data.get('parts').trim();
          body.servico = data.get('resolution').trim();
          body.verificacao = data.get('validation').trim();
        } else {
          body.motivo = data.get('note').trim();
          if ((action === 'approve' && initial) || action === 'priority') {
            body.prioridade = data.get('priority');
            if (!body.prioridade) { this.showToast('Selecione a prioridade da manutenção.', 'error'); return; }
            if (action === 'priority' && body.prioridade === order.priority) { this.showToast('Escolha uma prioridade diferente.', 'error'); return; }
          }
          if (['return','priority','escalate'].includes(action) && !body.motivo) {
            this.showToast('Descreva o motivo para registrar esta ação.', 'error');
            form.elements.note.focus();
            return;
          }
        }
      }
      this.orderBusy = true;
      try {
        const updated = await AgroSenseAuth.patch('/api/ordens-servico/' + order.apiId + '/status', body);
        this.showToast(updated.numero + ' · ' + AgroOrdersApi.states[updated.status]);
        await this.loadOrders();
      } catch (err) { this.showToast(err.message,'error'); } finally { this.orderBusy=false; }
    },
    async recognize(alert) {
      if (this.recognizingAlert) return;
      this.recognizingAlert = true;
      try {
        await AgroAlertsApi.recognize(alert.id,'');
        this.showToast('Alerta reconhecido.');
        await this.tick();
      } catch (err) { this.showToast(err.message,'error'); }
      finally { this.recognizingAlert = false; }
    },
    captureReport() {
      this.reportSnapshot = JSON.parse(JSON.stringify({machines:this.machines, events:this.events, time:this.now,
        start:this.machineHistory.at(-1)?.time || this.now}));
    },
    async generateReport() {
      if (this.generatingReport) return;
      this.generatingReport = true;
      try {
        if (!await this.tick()) return;
        const time = Date.now();
        const period = this.reportPeriod;
        const query = new URLSearchParams({periodo:period, fim:new Date(time).toISOString()});
        const rows = await AgroSenseAuth.get('/api/leituras/resumo?' + query);
        const byId = new Map(rows.map(r => [r.machine_id,r]));
        const machines = this.machines.map(m => {
          const r = byId.get(m.id);
          if (!r) return {...m,summary:null};
          const metric = k => ({min:r[k+'_min'],avg:r[k+'_avg'],max:r[k+'_max']});
          return {...m,summary:{n:r.n,failures:r.failures,temp:metric('temp'),rpm:metric('rpm'),pressao:metric('torque')}};
        });
        const start = period === 'all' ? rows.reduce((v,r)=>Math.min(v,Date.parse(r.inicio)),time)
          : time - (period === 'hour' ? 3600000 : 86400000);
        this.reportSnapshot = {machines,events:this.events.filter(e=>e.time>=start && e.time<=time),time,start,period};
        this.showToast('Relatório atualizado.');
      } catch (err) { this.showToast(err.message,'error'); }
      finally { this.generatingReport = false; }
    },
    printReport() { this.$nextTick(() => window.print()); },
    resetRecord() { this.captureReport(); }

  }
});

// Registra a diretiva na instância existente; não introduz outra dependência.
app.directive('number-pop', numberPop);
AgroChart.mixins = [AgroChartTheme];
SectorChart.mixins = [AgroChartTheme];
TelemetryChart.mixins = [AgroChartTheme];
app.component('agro-chart', AgroChart);
app.component('sector-chart', SectorChart);
app.component('telemetry-chart', TelemetryChart);
app.component('agro-select', AgroSelect);
app.mount('#app');
document.getElementById('app-startup').hidden = true;

})().catch(err => {
  console.error('Falha ao iniciar AgroSense:', err);
  document.getElementById('app').hidden = true;
  document.getElementById('app-startup-message').textContent = err.code
    ? err.message : 'Tente abrir o sistema novamente. Se o problema continuar, avise o suporte.';
  document.getElementById('app-startup').hidden = false;
});
