$ErrorActionPreference = 'Stop'
$agrosenseLocalAnterior = Get-Location
$agrosenseSaida = 1
try {
    Set-Location -LiteralPath $PSScriptRoot
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw 'Instale o Node.js para iniciar o frontend.'
    }
    & node .\server.cjs
    $agrosenseSaida = $LASTEXITCODE
}
finally {
    Set-Location -LiteralPath $agrosenseLocalAnterior.Path
}
exit $agrosenseSaida
