$ErrorActionPreference = 'Stop'
$agrosenseSenhaAnterior = $env:AGROSENSE_DB_PASSWORD
$agrosenseLocalAnterior = Get-Location
$agrosenseSaida = 1

try {
    Set-Location -LiteralPath $PSScriptRoot
    $agrosenseSenha = Read-Host 'Senha PostgreSQL da conta agrosense' -AsSecureString
    $agrosenseCredencial = [System.Management.Automation.PSCredential]::new('agrosense', $agrosenseSenha)
    $env:AGROSENSE_DB_PASSWORD = $agrosenseCredencial.GetNetworkCredential().Password
    & .\mvnw.cmd spring-boot:run
    $agrosenseSaida = $LASTEXITCODE
}
finally {
    $env:AGROSENSE_DB_PASSWORD = $agrosenseSenhaAnterior
    if ($null -ne $agrosenseSenha) { $agrosenseSenha.Dispose() }
    $agrosenseCredencial = $null
    Set-Location -LiteralPath $agrosenseLocalAnterior.Path
}

exit $agrosenseSaida
