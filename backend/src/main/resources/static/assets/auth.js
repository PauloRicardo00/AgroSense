const mostrar = document.getElementById('mostrar-senha');
if (mostrar) {
    mostrar.addEventListener('click', () => {
        const senha = document.getElementById('senha');
        const visivel = senha.type === 'password';
        senha.type = visivel ? 'text' : 'password';
        mostrar.textContent = visivel ? 'Ocultar senha' : 'Mostrar senha';
        mostrar.setAttribute('aria-pressed', String(visivel));
    });
}
