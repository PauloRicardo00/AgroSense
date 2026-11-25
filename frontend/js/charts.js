/* AgroSense: preserve fleetChart averages; only the rendering changes. */
echarts.registerTheme('agrosense', {
  color: ['#294D3E', '#A87532'],
  textStyle: { fontFamily: 'Inter, sans-serif', color: '#24342B' },
  backgroundColor: 'transparent'
});
const AgroChart = {
  props: ['chart', 'meta'],
  template: `<div class="ag-chart-wrap"><div ref="plot" class="ag-chart" tabindex="0" :aria-label="summary" @keydown.left.prevent="inspect(-1)" @keydown.right.prevent="inspect(1)" @blur="hideTip"></div><p v-if="!hasData" class="ag-chart-empty">Sem leituras disponíveis para esta frota.</p><p class="sr-only">Use as setas esquerda e direita para consultar as amostras.</p><span class="sr-only" aria-live="polite">{{ selection }}</span></div>`,
  data() { return { selection: '', index: -1 }; },
  computed: {
    hasData() { return this.chart.lines.some(l => l.values.length); },
    summary() { return this.meta.label + ': média por categoria. ' + this.chart.lines.map(l => l.label + ': ' + AgroData.fmt(l.last, this.meta.digits) + ' ' + this.meta.unit).join('. '); }
  },
  mounted() {
    this.plot = Vue.markRaw(echarts.init(this.$refs.plot, 'agrosense', { renderer: 'svg' }));
    this.motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    this.onMotion = () => this.render();
    this.motion.addEventListener('change', this.onMotion);
    this.observer = new ResizeObserver(() => this.plot.resize());
    this.observer.observe(this.$refs.plot);
    this.render();
  },
  watch: {
    chart: { deep: true, handler() { this.render(); } },
    meta() { this.index = -1; this.selection = ''; this.hideTip(); this.render(); }
  },
  beforeUnmount() { this.observer.disconnect(); this.motion.removeEventListener('change', this.onMotion); this.plot.dispose(); },
  methods: {
    hideTip() { this.plot?.dispatchAction({ type: 'hideTip' }); },
    inspect(direction) {
      const count = Math.max(0, ...this.chart.lines.map(l => l.values.length));
      if (!count) return;
      this.index = Math.max(0, Math.min(count - 1, (this.index < 0 ? count - 1 : this.index) + direction));
      this.plot.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: this.index });
      this.selection = this.chart.lines.map(l => `${l.label}: ${AgroData.fmt(l.values[this.index], this.meta.digits)} ${this.meta.unit}`).join('. ');
    },
    render() {
      if (!this.plot) return;
      const { chart, meta } = this;
      this.plot.setOption({
        animation: !this.motion.matches, animationDuration: 320, animationDurationUpdate: 280, animationEasingUpdate: 'cubicOut',
        grid: { top: 30, right: 18, bottom: 28, left: 12, containLabel: true },
        tooltip: { trigger: 'axis', confine: true, renderMode: 'richText', backgroundColor: '#fff', borderColor: '#DCE6DD', padding: 12,
          valueFormatter: v => `${AgroData.fmt(v, meta.digits)} ${meta.unit}` },
        xAxis: { type: 'value', max: 0, name: '', axisLine: { lineStyle: { color: '#DCE6DD' } }, splitLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#657369', formatter: v => v === 0 ? 'Agora' : `${v} s` } },
        yAxis: { type: 'value', name: meta.unit, min: this.hasData ? chart.min : 0, max: this.hasData ? chart.max : 1, splitNumber: 3,
          axisLabel: { color: '#657369', formatter: v => AgroData.fmt(v, meta.digits) }, splitLine: { lineStyle: { color: '#E7EDE7', type: 'dashed' } } },
        series: chart.lines.map((line, i) => ({ id: line.key, name: line.label, type: 'line', smooth: false, showSymbol: false, symbolSize: 6,
          lineStyle: { width: 2, type: i ? 'dashed' : 'solid' }, emphasis: { focus: 'series' },
          data: line.values.map((v, index) => [-(line.values.length - 1 - index) * 2, v]) }))
      }, { replaceMerge: ['series'] });
    }
  }
};

const sectorConditions = [
  {key:'ok', label:'Sem desvio detectado', color:'#287A52'},
  {key:'warn', label:'Em atenção', color:'#B67B20'},
  {key:'crit', label:'Crítica', color:'#B94145'},
  {key:'unknown', label:'Sem avaliação atual', color:'#667775'}
];

/* Por setor: condições operacionais calculadas com as leituras do backend. */
const SectorChart = {
  props: ['rows', 'selected'],
  emits: ['select'],
  template: `<div class="ag-chart-wrap"><div ref="plot" class="ag-chart sector-chart" role="img" :aria-label="summary"></div>
    <ul class="ag-legend"><li v-for="l in legend" :key="l.k"><i :style="{ background: l.color }"></i>{{ l.label }} <b>{{ l.n }}</b></li></ul></div>`,
  computed: {
    legend() {
      return sectorConditions.map(({key:k,label,color}) => ({
        k, label, color, n:this.rows.reduce((total, row) => total + row[k], 0)
      }));
    },
    summary() { return 'Condição das máquinas por setor. ' + this.rows.map(r =>
      r.s.nome + ': ' + sectorConditions.map(({key, label}) => r[key] + ' ' + label.toLowerCase()).join(', ')
    ).join('. '); }
  },
  mounted() {
    this.plot = Vue.markRaw(echarts.init(this.$refs.plot, 'agrosense', { renderer: 'svg' }));
    this.plot.on('click', p => { const r = this.rows[p.dataIndex]; if (r) this.$emit('select', r.s.nome); });
    this.plot.getZr().on('click', e => { if (!e.target) return; });
    this.observer = new ResizeObserver(() => {
      this.plot.resize();
      this.render();
    });
    this.observer.observe(this.$refs.plot);
    this.render();
  },
  watch: { rows: { deep: true, handler() { this.render(); } }, selected() { this.render(); } },
  beforeUnmount() { this.observer.disconnect(); this.plot.dispose(); },
  methods: {
    render() {
      const rows = this.rows, sel = this.selected;
      const labelWidth = Math.floor((this.plot.getWidth() - 36) / rows.length) - 4;
      const maxCharacters = Math.max(5, Math.floor(labelWidth / 6));
      const sectorLabel = (value, index) => {
        const lines = [];
        let line = '';
        const label = (rows[index]?.crit ? '▲ ' : '') + value.replace(/^Setor /i, '');
        for (const text of label.split(/\s+/)) {
          let word = text;
          if (line && line.length + word.length + 1 > maxCharacters) {
            lines.push(line);
            line = '';
          }
          while (word.length > maxCharacters) {
            lines.push(word.slice(0, maxCharacters));
            word = word.slice(maxCharacters);
          }
          line = line ? line + ' ' + word : word;
        }
        if (line) lines.push(line);
        return lines.join('\n');
      };
      const mk = (key, name, color) => ({ name, type: 'bar', stack: 'total', barWidth: '46%', barMaxWidth: 40, cursor: 'pointer',
        itemStyle: { color, borderColor: '#fff', borderWidth: 1 },
        data: rows.map(r => ({ value: r[key], itemStyle: { color, opacity: !sel || sel === r.s.nome ? 1 : .5 } })),
        label: { show: true, formatter: p => p.value ? p.value : '', color: '#fff', fontSize: 11, fontWeight: 600 },
        emphasis: { disabled: true } });
      this.plot.setOption({
        animation: false,
        grid: { top: 26, right: 4, bottom: 4, left: 4, containLabel: true },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(18,58,46,.06)' } }, confine: true, backgroundColor: '#fff', borderColor: '#DCE5DF', padding: [8, 10], textStyle: { color: '#182723', fontSize: 12 },
          formatter: ps => { const r = rows[ps[0]?.dataIndex]; if (!r) return '';
            const line = ({key,label,color}) => `<div style="display:flex;justify-content:space-between;gap:18px"><span><i style="display:inline-block;width:8px;height:8px;border-radius:2px;background:${color};margin-right:6px"></i>${label}</span><b>${r[key]}</b></div>`;
            return `<div style="font-weight:600;margin-bottom:4px">${echarts.format.encodeHTML(r.s.nome)} · ${r.total} ${r.total === 1 ? 'máquina' : 'máquinas'}</div>${sectorConditions.map(line).join('')}<div style="margin-top:5px;color:#64736D;font-size:11px">Clique para listar os equipamentos</div>`; } },
        xAxis: { type: 'category', data: rows.map(r => r.s.nome), axisTick: { show: false }, axisLine: { lineStyle: { color: '#C9D5CD' } },
          axisLabel: { interval: 0, hideOverlap: true, color: '#3C4B45', fontSize: 11.5, lineHeight: 15, margin: 10, formatter: sectorLabel } },
        yAxis: { type: 'value', name: 'máquinas', nameGap: 10, nameTextStyle: { color: '#64736D', fontSize: 11, align: 'left' }, minInterval: 1, axisLabel: { color: '#64736D', fontSize: 11 }, splitLine: { lineStyle: { color: '#E8EEEA' } } },
        series: sectorConditions.map(({key,label,color}) => mk(key,label,color))
      }, true);
    }
  }
};

/* Telemetria: uma série por máquina (histórico real da simulação), com limites da categoria quando o escopo tem uma só. */
const TelemetryChart = {
  props: ['series', 'limits', 'meta'],
  template: `<div class="ag-chart-wrap"><div ref="plot" class="ag-chart tele-chart" tabindex="0" role="img" :aria-label="summary" @keydown.left.prevent="inspect(-1)" @keydown.right.prevent="inspect(1)" @blur="hideTip"></div><span class="sr-only" aria-live="polite">{{ selection }}</span></div>`,
  data() { return { selection: '', sampleTime: null }; },
  computed: {
    summary() { return this.meta.label + ': ' + this.series.map(s => s.id + ' ' + AgroData.fmt(s.now, this.meta.digits) + ' ' + this.meta.unit).join('; ') + '. Use as setas para consultar as amostras.'; }
  },
  mounted() {
    this.plot = Vue.markRaw(echarts.init(this.$refs.plot, 'agrosense', { renderer: 'svg' }));
    this.motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    this.observer = new ResizeObserver(() => this.plot.resize());
    this.observer.observe(this.$refs.plot);
    this.render();
  },
  watch: { series: { deep: true, handler() { this.render(); } }, limits: { deep: true, handler() { this.render(); } }, meta() { this.sampleTime = null; this.selection = ''; this.render(); } },
  beforeUnmount() { this.observer.disconnect(); this.plot.dispose(); },
  methods: {
    hideTip() { this.plot && this.plot.dispatchAction({ type: 'hideTip' }); },
    inspect(d) {
      const times = [...new Set(this.series.flatMap(s => s.times.filter((time, i) => Number.isFinite(time) && Number.isFinite(s.values[i]))))].sort((a, b) => a - b);
      if (!times.length) return;
      const index = times.indexOf(this.sampleTime);
      this.sampleTime = times[index < 0 ? times.length - 1 : Math.max(0, Math.min(times.length - 1, index + d))];
      const samples = this.series.flatMap((s, seriesIndex) => {
        const dataIndex = s.times.findIndex((time, i) => time === this.sampleTime && Number.isFinite(s.values[i]));
        return dataIndex < 0 ? [] : [{ s, seriesIndex, dataIndex }];
      });
      this.plot.dispatchAction({ type: 'showTip', seriesIndex: samples[0].seriesIndex, dataIndex: samples[0].dataIndex });
      this.selection = new Date(this.sampleTime).toLocaleString('pt-BR') + '. ' + samples.map(({ s, dataIndex }) =>
        s.id + ': ' + AgroData.fmt(s.values[dataIndex], this.meta.digits) + ' ' + this.meta.unit).join('. ');
    },
    render() {
      if (!this.plot) return;
      const { series, limits, meta } = this, f = v => AgroData.fmt(v, meta.digits);
      const LC = ['#5C8A73', '#B67B20', '#B94145', '#667775'], SYM = ['✓', '●', '▲', '–'];
      const vals = [...series.flatMap(s => s.values), ...limits.map(l => l.v)].filter(Number.isFinite);
      if (!vals.length) return;
      const low = Math.min(...vals), high = Math.max(...vals);
      const precision = 10 ** -meta.digits;
      const pad = Math.max(precision, (high - low) * .10);
      const targetInterval = Math.max(precision, (high - low + 2 * pad) / 5);
      const magnitude = 10 ** Math.floor(Math.log10(targetInterval));
      const interval = [1, 2, 5, 10].find(factor => factor * magnitude >= targetInterval) * magnitude;
      const lo = Number((Math.floor((low - pad) / interval) * interval).toFixed(meta.digits));
      const hi = Number((Math.ceil((high + pad) / interval) * interval).toFixed(meta.digits));
      const times = series.flatMap(s => s.times.filter((time, i) => Number.isFinite(time) && Number.isFinite(s.values[i])));
      if (!times.length) return;
      const firstTime = Math.min(...times), lastTime = Math.max(...times);
      const timePadding = firstTime === lastTime ? 5000 : 1000;
      const only = series.length === 1;
      const showEndLabels = series.length <= 12;
      const lines = series.map(s => { const col = only && !s.level ? '#123A2E' : LC[s.level];
        return { id: s.id, name: s.id, type: 'line', showSymbol: s.values.filter(Number.isFinite).length === 1, symbolSize: 7, z: 2 + s.level, smooth: false,
          itemStyle: { color: col },
          lineStyle: { width: s.level === 2 ? 2.6 : only ? 2.4 : s.level ? 2.2 : 1.5, color: col, opacity: only || s.level ? 1 : .8 },
          emphasis: { focus: 'series', lineStyle: { width: 3 } },
          endLabel: { show: showEndLabels, formatter: '{a}', width: 110, overflow: 'truncate', color: col, fontSize: 11, fontWeight: 600 }, labelLayout: { hideOverlap: true },
          data: s.values.map((v, i) => [s.times[i], v]) }; });
      lines.push({ id: 'limits', name: 'limites', type: 'line', silent: true, data: [], markLine: { silent: true, symbol: 'none', animation: false,
        data: limits.map(l => ({ yAxis: l.v, lineStyle: { color: l.level === 2 ? '#B94145' : '#B67B20', type: 'dashed', width: 1.2 },
          label: { formatter: `${l.label} ${f(l.v)} ${meta.unit}`, position: 'insideStartTop', color: l.level === 2 ? '#9E2F33' : '#7A5010', fontSize: 11 } })) } });
      this.plot.setOption({
        animation: !this.motion.matches, animationDuration: 200, animationDurationUpdate: 200,
        grid: { top: 30, right: showEndLabels ? 124 : 14, bottom: 4, left: 6, containLabel: true },
        tooltip: { trigger: 'axis', confine: true, backgroundColor: '#fff', borderColor: '#DCE5DF', padding: [8, 10], textStyle: { color: '#182723', fontSize: 12 },
          formatter: ps => { const real = ps.filter(p => p.seriesId !== 'limits' && Number.isFinite(p.value?.[1])); if (!real.length) return '';
            const x = real[0].value[0]; const head = new Date(x).toLocaleString('pt-BR');
            return `<div style="font-weight:600;margin-bottom:4px">${head}</div>` + real.sort((a, b) => b.value[1] - a.value[1]).map(p => { const s = series.find(q => q.id === p.seriesId);
              return `<div style="display:flex;justify-content:space-between;gap:16px"><span style="color:${LC[s.level]}">${SYM[s.level]} <b>${echarts.format.encodeHTML(s.id)}</b></span><span>${f(p.value[1])} ${meta.unit} · ${echarts.format.encodeHTML(s.label)}</span></div>`; }).join(''); } },
        xAxis: { type: 'time', min: firstTime - timePadding, max: lastTime + timePadding, minInterval: 1000,
          splitLine: { show: false }, axisTick: { show: false }, axisLine: { lineStyle: { color: '#C9D5CD' } },
          axisLabel: { color: '#64736D', fontSize: 11, formatter: v => {
            const age = Math.round((lastTime - v) / 1000);
            return age > 0 ? '−' + age + ' s' : 'Última leitura';
          } } },
        yAxis: { type: 'value', min: lo, max: hi, interval: interval, name: meta.unit, nameTextStyle: { color: '#64736D', fontSize: 11, align: 'left' },
          axisLine: { show: false, onZero: false }, axisTick: { show: false },
          axisLabel: { color: '#64736D', fontSize: 11, formatter: f }, splitLine: { lineStyle: { color: '#E8EEEA' } } },
        series: lines
      }, true);
    }
  }
};
