# Ejecutar desde PowerShell del usuario (Docker Desktop abierto): .\deploy\local-demo.ps1
# No borra volumenes, no promueve PostgreSQL y solo elimina el archivo del usuario de demo.
param([ValidatePattern('^[a-z0-9][a-z0-9-]+$')][string]$ProjectName = ('dfsha-demo-' + (Get-Date -Format yyyyMMddHHmmss)))
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskPreviousLocation = Get-Location
$taskDocker = (Get-Command docker -ErrorAction Stop).Source
$taskJava = (Get-Command java -ErrorAction Stop).Source
$taskBash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
$taskRoot = Join-Path $PSScriptRoot '.demo'
$taskBuild = Join-Path $taskRoot 'build'
$taskRun = Join-Path $taskRoot $ProjectName
$taskCompose = @('compose', '-p', $ProjectName, '-f', (Join-Path $PSScriptRoot 'docker-compose.yml'),
    '-f', (Join-Path $PSScriptRoot 'docker-compose.local-demo.yml'))
$taskEnv = @{ POSTGRES_PORT='55433'; RABBITMQ_AMQP_PORT='5673'; ADVERTISE_HOST='localhost';
    BLOCK_SIZE_BYTES='8388608'; REPLICATION_FACTOR='3'; DFSHA_DEMO_TAG=$ProjectName }
$taskSavedEnv = @{}
$taskStarted = $false
$taskStage = 'preparacion'

function Invoke-DemoCompose([string[]]$Arguments) {
    & $taskDocker @taskCompose @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Docker Compose fallo: ' + ($Arguments -join ' ')) }
}

function Read-DemoSql([string]$Sql) {
    $sqlArgs = @('exec', '-T', 'postgres', 'psql', '-U', 'dfsha', '-d', 'dfsha', '-v', 'ON_ERROR_STOP=1', '-tAc', $Sql)
    $value = & $taskDocker @taskCompose @sqlArgs
    if ($LASTEXITCODE -ne 0) { throw 'Fallo una consulta de comprobacion PostgreSQL' }
    return (($value | Out-String).Trim())
}

function Invoke-DemoClient([string[]]$Commands, [string]$LogName) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $taskJava
    $info.Arguments = '-jar "' + (Join-Path $taskRepo 'dfsha-client\target\dfsha-client.jar') + '"'
    $info.WorkingDirectory = $taskRun
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['CONTROLNODE_ENDPOINTS'] = 'localhost:50051,localhost:50052'
    $info.EnvironmentVariables['TLS_CA_FILE'] = Join-Path $PSScriptRoot 'secrets\ca.crt'
    $process = [System.Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    foreach ($command in $Commands) { $process.StandardInput.WriteLine($command) }
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(120000)) {
        $process.Kill() # exclusivamente el proceso de cliente que acaba de iniciar este script
        $process.WaitForExit()
        throw 'El cliente de demo excedio 120 segundos'
    }
    $result = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $taskRun $LogName), $result)
    Write-Host $result
    if ($process.ExitCode -ne 0 -or $result -match '(?m)Error:') { throw ('La CLI reporto un error; revisar ' + $LogName) }
}

try {
    Set-Location -LiteralPath $taskRepo
    & $taskDocker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine no esta accesible desde esta terminal' }
    $existing = & $taskDocker volume ls --filter ('label=com.docker.compose.project=' + $ProjectName) --format '{{.Name}}'
    if ($LASTEXITCODE -ne 0 -or $existing) { throw 'El nombre elegido ya tiene volumenes o no pudo comprobarse. Elegir un ProjectName nuevo; no se borrara nada.' }
    foreach ($port in @(50051,50052,50061,50062,50063,50064,55433,5673,15672)) {
        $probe = New-Object System.Net.Sockets.TcpClient
        try {
            $connect = $probe.ConnectAsync('127.0.0.1', $port)
            try { [void]$connect.Wait(300) } catch { }
            if ($probe.Connected) { throw "El puerto $port ya esta ocupado. No se detendra ningun servicio ajeno." }
        } finally { $probe.Dispose() }
    }
    foreach ($name in $taskEnv.Keys) {
        $taskSavedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $taskEnv[$name], 'Process')
    }
    $sourceFiles = foreach ($module in @('dfsha-proto','dfsha-common','dfsha-client','dfsha-controlnode','dfsha-datanode')) {
        Get-ChildItem -LiteralPath (Join-Path $taskRepo ($module + '\src\main')) -Recurse -File |
            Where-Object { $_.Extension -in '.java','.proto' }
        Get-Item -LiteralPath (Join-Path $taskRepo ($module + '\pom.xml'))
    }
    $sourceFiles += Get-Item -LiteralPath (Join-Path $taskRepo 'pom.xml')
    $latest = $sourceFiles | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    [void](New-Item -ItemType Directory -Path $taskBuild -Force)
    $jarHashes = @{}
    foreach ($module in @('dfsha-client','dfsha-controlnode','dfsha-datanode')) {
        $jar = Join-Path $taskRepo ($module + '\target\' + $module + '.jar')
        if (-not (Test-Path -LiteralPath $jar)) { throw "Falta $jar. Ejecutar mvn package antes." }
        if ((Get-Item -LiteralPath $jar).LastWriteTimeUtc -lt $latest.LastWriteTimeUtc) { throw "JAR anterior al codigo: $module. Volver a ejecutar mvn package." }
        $jarHashes[$module] = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
        Copy-Item -LiteralPath $jar -Destination (Join-Path $taskBuild ($module + '.jar'))
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Dockerfile.local-demo') -Destination (Join-Path $taskBuild 'Dockerfile')
    if (-not (Test-Path -LiteralPath $taskBash)) { throw 'No se encontro Git Bash. Generar secretos con bash deploy/gen-secrets.sh desde deploy/.' }
    Push-Location -LiteralPath $PSScriptRoot
    try {
        & $taskBash './gen-secrets.sh'
        if ($LASTEXITCODE -ne 0) { throw 'No se pudieron generar los secretos; no se continua' }
    } finally { Pop-Location }
    foreach ($secret in @('postgres_password.txt','rabbitmq_password.txt','cluster_registration_key.txt','storage_key.txt',
            'token_private.pem','token_public.pem','ca.crt','node.crt','node.key')) {
        $file = Join-Path $PSScriptRoot ('secrets\' + $secret)
        if (-not (Test-Path -LiteralPath $file) -or (Get-Item -LiteralPath $file).Length -eq 0) { throw "Falta secreto valido: $secret" }
    }
    [void](New-Item -ItemType Directory -Path $taskRun)
    Write-Host "PROYECTO DE DEMO: $ProjectName"
    Write-Host "EVIDENCIA: $taskRun"
    $taskStage = 'imagen y arranque'
    Invoke-DemoCompose -Arguments @('config', '--quiet')
    Invoke-DemoCompose -Arguments @('build', 'controlnode1')
    $taskStarted = $true
    Invoke-DemoCompose -Arguments @('up', '-d', '--no-build')
    $until = [DateTime]::UtcNow.AddSeconds(180)
    do {
        Start-Sleep -Seconds 3
        try { $alive = Read-DemoSql "SELECT count(*) FROM datanode WHERE status = 'ALIVE'" } catch { $alive = '0' }
    } while ($alive -ne '4' -and [DateTime]::UtcNow -lt $until)
    if ($alive -ne '4') { throw 'No se registraron los cuatro DataNodes en 180 segundos' }
    Write-Host 'PASA: cuatro DataNodes registrados'

    $taskStage = 'subida y descarga'
    $bytes = New-Object byte[] (20 * 1024 * 1024)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllBytes((Join-Path $taskRun 'original.bin'), $bytes)
    [IO.File]::WriteAllText((Join-Path $taskRun 'texto.txt'), 'DFSHA-DEMO-LOCAL: lectura parcial y bloqueos', (New-Object Text.UTF8Encoding($false)))
    $user = 'demo' + ([Guid]::NewGuid().ToString('N').Substring(0,12))
    $password = [Guid]::NewGuid().ToString('N') # usuario desechable, nunca imprimir ni registrar su clave
    $login = "login $user $password"
    Invoke-DemoClient -Commands @("register $user $password", $login, 'mkdir /demo', 'put original.bin /demo/archivo20.bin',
        'put texto.txt /demo/texto.txt', 'ls /demo', 'get /demo/archivo20.bin descargado.bin',
        'cat /demo/texto.txt 0 16', 'lock /demo/texto.txt 60', 'unlock /demo/texto.txt', 'exit') -LogName 'cliente.log'
    $original = (Get-FileHash -LiteralPath (Join-Path $taskRun 'original.bin') -Algorithm SHA256).Hash
    $download = (Get-FileHash -LiteralPath (Join-Path $taskRun 'descargado.bin') -Algorithm SHA256).Hash
    if ($original -ne $download) { throw 'El SHA-256 descargado no coincide' }
    Write-Host "PASA: 20 MiB subidos/descargados con SHA-256 $original"

    $taskStage = 'replicas'
    $fileWhere = "FROM dfs_file f JOIN app_user u ON u.user_id = f.owner_id JOIN dfs_block b ON b.file_id = f.file_id JOIN block_location l ON l.block_id = b.block_id JOIN datanode d ON d.datanode_id = l.datanode_id WHERE u.username = '$user' AND f.path = '/demo/archivo20.bin' AND f.status = 'COMMITTED'"
    $until = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $copies = Read-DemoSql "SELECT coalesce(min(n),0) FROM (SELECT count(*) FILTER (WHERE l.state = 'WRITTEN' AND d.status = 'ALIVE') n $fileWhere GROUP BY b.block_id) counts"
        if ([int]$copies -lt 3) { Start-Sleep -Seconds 2 }
    } while ([int]$copies -lt 3 -and [DateTime]::UtcNow -lt $until)
    if ([int]$copies -lt 3) { throw 'No se alcanzo factor 3 en todos los bloques' }
    $ids = (Read-DemoSql "SELECT DISTINCT b.block_id $fileWhere") -split '\s+'
    if ($ids.Count -ne 3) { throw '20 MiB no se dividieron en tres bloques' }
    Write-Host 'PASA: tres bloques, cada uno con al menos tres copias vivas'

    $taskStage = 'borrado'
    Invoke-DemoClient -Commands @($login, 'rm /demo/archivo20.bin', 'ls /demo', 'exit') -LogName 'borrado.log'
    $idList = ($ids | ForEach-Object { "'$_'::uuid" }) -join ','
    $until = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $pending = Read-DemoSql "SELECT count(*) FROM block_delete_outbox WHERE block_id IN ($idList) AND published_at IS NULL"
        $present = 0
        foreach ($node in @('datanode1','datanode2','datanode3','datanode4')) {
            foreach ($id in $ids) {
                & $taskDocker @taskCompose exec -T $node sh -c 'test ! -e $1' sh "/data/blocks/$id.blk"
                if ($LASTEXITCODE -ne 0) { $present++ }
            }
        }
        if ($pending -ne '0' -or $present -ne 0) { Start-Sleep -Seconds 2 }
    } while (($pending -ne '0' -or $present -ne 0) -and [DateTime]::UtcNow -lt $until)
    if ($pending -ne '0' -or $present -ne 0) { throw 'Quedan tareas o bloques sin eliminar; no se afirma borrado fisico' }
    Write-Host 'PASA: borrado publicado y ninguno de los tres bloques presente en los cuatro DataNodes'
    $receipt = [ordered]@{ Fecha=(Get-Date -Format o); Proyecto=$ProjectName; Commit=((& git rev-parse HEAD) | Out-String).Trim();
        UsuarioDemo=$user; Bytes=20971520; SHA256Original=$original; SHA256Descarga=$download; Bloques=$ids;
        CopiasMinimas=[int]$copies; BorradoFisico=$true; JarSHA256=$jarHashes;
        Limites='No certifica failover, caida del broker, carga, DLQ, escrituras tardias ni inmutabilidad ante reenvios distintos.' }
    [IO.File]::WriteAllText((Join-Path $taskRun 'resultado.json'), ($receipt | ConvertTo-Json -Depth 5))
    Write-Host "DEMO BASICA COMPLETADA. Evidencia: $taskRun\resultado.json"
    Write-Host "El cluster queda encendido. Para detener SOLO esta demo sin borrar datos: docker compose -p $ProjectName -f deploy/docker-compose.yml -f deploy/docker-compose.local-demo.yml stop"
} catch {
    Write-Host "DEMO NO COMPLETADA en '$taskStage': $($_.Exception.Message)" -ForegroundColor Red
    if ($taskStarted) { & $taskDocker @taskCompose ps; & $taskDocker @taskCompose logs --tail 40 controlnode1 controlnode2 datanode1 postgres }
    throw
} finally {
    foreach ($name in $taskSavedEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $taskSavedEnv[$name], 'Process') }
    Set-Location -LiteralPath $taskPreviousLocation.Path
}
