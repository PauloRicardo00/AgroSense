/* Adaptador visual: altera somente cores, nunca séries ou valores. */
const AgroChartTheme = {
  mounted() {
    this.$nextTick(() => {
    const chart = this.plot;
    const original = chart.setOption.bind(chart);
    const map = {'#DCE5DF':'#46554c','#182723':'#edf4ef','#24342B':'#edf4ef','#3C4B45':'#d2ded7','#64736D':'#b1c3b8','#C9D5CD':'#53675a','#E8EEEA':'#405046','#5C8A73':'#96c5aa','#123A2E':'#96c5aa','#9E2F33':'#ffaaa5','#7A5010':'#efc46f','#657369':'#b1c3b8','#DCE6DD':'#46554c','#E7EDE7':'#405046'};
    const convert = (value, property) => {
      if(typeof value === 'string') return property === 'backgroundColor' && value === '#fff' ? '#252c28' : map[value] || value;
      if(Array.isArray(value)) return value.map(item=>convert(item));
      if(value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key,val])=>[key,convert(val,key)]));
      return value;
    };
    chart.setOption = (options,...args) => original(document.documentElement.dataset.theme === 'dark' ? convert(options) : options,...args);
    this.themeObserver = new MutationObserver(()=>this.render());
    this.themeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
    this.render();
    });
  },
  beforeUnmount() { this.themeObserver?.disconnect(); }
};
