# Sincroniza solamente los usuarios de aplicacion de una demo local existente.
param([ValidatePattern('^dfsha-demo-[a-z0-9-]+$')][string]$ProjectName = 'dfsha-demo-20261025233922')
$ErrorActionPreference = 'Stop'
$taskDocker = (Get-Command docker -ErrorAction Stop).Source
$taskTargets = @(
    @{ Container=($ProjectName + '-rabbitmq-1'); Mode='rabbitmq' },
    @{ Container=($ProjectName + '-postgres-1'); Mode='postgres' }
)
foreach ($taskTarget in $taskTargets) {
    $taskLabelsJson = & $taskDocker inspect --format '{{json .Config.Labels}}' $taskTarget.Container
    if ($LASTEXITCODE -ne 0) { throw ('No se pudo comprobar ' + $taskTarget.Container) }
    $taskLabels = ($taskLabelsJson | Out-String) | ConvertFrom-Json
    if ($taskLabels.'com.docker.compose.project' -ne $ProjectName) { throw 'El contenedor no pertenece a la demo indicada' }
}
foreach ($taskTarget in $taskTargets) {
    $taskRemote = '/tmp/dfsha-demo-credentials.sh'
    & $taskDocker cp (Join-Path $PSScriptRoot 'repair-demo-credentials.sh') ($taskTarget.Container + ':' + $taskRemote)
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo copiar el reparador' }
    & $taskDocker exec $taskTarget.Container sh $taskRemote $taskTarget.Mode
    if ($LASTEXITCODE -ne 0) { throw ('No se pudo sincronizar ' + $taskTarget.Mode) }
}
Write-Host 'Credenciales de aplicacion sincronizadas; replicator y volumenes conservados.'
