# Ejecutar desde PowerShell del usuario (Docker Desktop abierto): .\deploy\local-demo.ps1
# No borra volumenes. -ValidateFailures promueve SOLO el standby de una demo NUEVA y segura.
param(
    [ValidatePattern('^dfsha-demo-[a-z0-9-]+$')][string]$ProjectName = ('dfsha-demo-' + (Get-Date -Format yyyyMMddHHmmss)),
    [switch]$Resume,
    [switch]$Secure,
    [switch]$ValidateFailures,
    [ValidateRange(0,8)][int]$LoadClients = 0
)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskPreviousLocation = Get-Location
$taskDocker = (Get-Command docker -ErrorAction Stop).Source
$taskJava = (Get-Command java -ErrorAction Stop).Source
$taskBash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
$taskRoot = Join-Path $PSScriptRoot '.demo'
$taskBuild = Join-Path $taskRoot 'build'
$taskRun = Join-Path $taskRoot $ProjectName
if ($Resume) { $taskRun = Join-Path $taskRoot ($ProjectName + '-resume-' + (Get-Date -Format HHmmss)) }
$taskCompose = @('compose', '-p', $ProjectName, '-f', (Join-Path $PSScriptRoot 'docker-compose.yml'),
    '-f', (Join-Path $PSScriptRoot 'docker-compose.local-demo.yml'))
if ($Secure) { $taskCompose += @('-f', (Join-Path $PSScriptRoot 'docker-compose.secure.yml')) }
$taskEnv = @{ POSTGRES_PORT='55433'; RABBITMQ_AMQP_PORT='5673'; ADVERTISE_HOST='localhost';
    BLOCK_SIZE_BYTES='8388608'; REPLICATION_FACTOR='3'; DFSHA_DEMO_TAG=$ProjectName;
    CONTROLNODE1_PORT='50051'; CONTROLNODE2_PORT='50052'; DATANODE1_PORT='50061'; DATANODE2_PORT='50062';
    DATANODE3_PORT='50063'; DATANODE4_PORT='50064'; RABBITMQ_MANAGEMENT_PORT='15672';
    DFSHA_SECRETS_DIR=('.demo/' + $ProjectName + '-secrets') }
if ($Resume -and -not $Secure -and -not (Test-Path (Join-Path $PSScriptRoot $taskEnv.DFSHA_SECRETS_DIR))) {
    $taskEnv.DFSHA_SECRETS_DIR = './secrets' # compatibilidad con la primera demo, no migra su esquema
}
if ($Secure) {
    $taskEnv.POSTGRES_PORT='55434'; $taskEnv.RABBITMQ_AMQP_PORT='5674'; $taskEnv.RABBITMQ_MANAGEMENT_PORT='15673'
    $taskEnv.CONTROLNODE1_PORT='50071'; $taskEnv.CONTROLNODE2_PORT='50072'
    $taskEnv.DATANODE1_PORT='50081'; $taskEnv.DATANODE2_PORT='50082'; $taskEnv.DATANODE3_PORT='50083'; $taskEnv.DATANODE4_PORT='50084'
}
$taskSecrets = Join-Path $PSScriptRoot $taskEnv.DFSHA_SECRETS_DIR
$taskDatabase = 'postgres'
$taskFailureResults = [ordered]@{}
$taskLoadResults = @()
$taskSavedEnv = @{}
$taskStarted = $false
$taskStage = 'preparacion'

function Invoke-DemoCompose([string[]]$Arguments) {
    & $taskDocker @taskCompose @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Docker Compose fallo: ' + ($Arguments -join ' ')) }
}

function Read-DemoSql([string]$Sql) {
    $sqlArgs = @('exec', '-T', $taskDatabase, 'psql', '-U', 'dfsha', '-d', 'dfsha', '-v', 'ON_ERROR_STOP=1', '-tAc', $Sql)
    $value = & $taskDocker @taskCompose @sqlArgs
    if ($LASTEXITCODE -ne 0) { throw 'Fallo una consulta de comprobacion PostgreSQL' }
    return (($value | Out-String).Trim())
}

function Start-DemoClient([string[]]$Commands, [string]$LogName, [switch]$WithoutTls) {
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
    $info.EnvironmentVariables['CONTROLNODE_ENDPOINTS'] = 'localhost:' + $taskEnv.CONTROLNODE1_PORT + ',localhost:' + $taskEnv.CONTROLNODE2_PORT
    $info.EnvironmentVariables['TLS_CA_FILE'] = if ($WithoutTls) { 'none' } else { Join-Path $taskSecrets 'ca.crt' }
    $process = [System.Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    foreach ($command in $Commands) { $process.StandardInput.WriteLine($command) }
    $process.StandardInput.Close()
    return [PSCustomObject]@{ Process=$process; Stdout=$stdout; Stderr=$stderr; LogName=$LogName }
}

function Complete-DemoClient($Run, [string]$ExpectedError = '') {
    $process = $Run.Process
    if (-not $process.WaitForExit(120000)) {
        $process.Kill() # exclusivamente el proceso de cliente que acaba de iniciar este script
        $process.WaitForExit()
        throw 'El cliente de demo excedio 120 segundos'
    }
    $result = $Run.Stdout.GetAwaiter().GetResult() + $Run.Stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $taskRun $Run.LogName), $result)
    Write-Host $result
    if ($process.ExitCode -ne 0) { throw ('La CLI termino con error: ' + $Run.LogName) }
    $errorsFound = @([regex]::Matches($result, '(?m)^.*Error:.*$'))
    if ($ExpectedError) {
        if ($errorsFound.Count -ne 1 -or $errorsFound[0].Value -notmatch ('Error: ' + [regex]::Escape($ExpectedError))) {
            throw ('No se observo un unico rechazo esperado ' + $ExpectedError + ' en ' + $Run.LogName)
        }
    } elseif ($errorsFound.Count -ne 0) { throw ('La CLI reporto un error; revisar ' + $Run.LogName) }
    $process.Dispose()
}

function Invoke-DemoClient([string[]]$Commands, [string]$LogName, [string]$ExpectedError = '', [switch]$WithoutTls) {
    $run = Start-DemoClient -Commands $Commands -LogName $LogName -WithoutTls:$WithoutTls
    Complete-DemoClient -Run $run -ExpectedError $ExpectedError
}

function Wait-DemoCheck([scriptblock]$Check, [int]$Seconds, [string]$Failure) {
    $until = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try { if ((& $Check) -eq $true) { return } } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $until)
    throw $Failure
}

function Wait-DemoDeletion([string[]]$BlockIds) {
    if (-not $BlockIds -or $BlockIds.Count -eq 0) { throw 'No hay UUID para comprobar borrado' }
    $idList = ($BlockIds | ForEach-Object { [void][Guid]::Parse($_); "'$_'::uuid" }) -join ','
    Wait-DemoCheck -Seconds 90 -Failure 'Quedan tareas o bloques sin eliminar' -Check {
        $pending = Read-DemoSql "SELECT count(*) FROM block_delete_outbox WHERE block_id IN ($idList) AND published_at IS NULL"
        $present = 0
        foreach ($node in @('datanode1','datanode2','datanode3','datanode4')) {
            foreach ($id in $BlockIds) {
                & $taskDocker @taskCompose exec -T $node sh -c 'test ! -e $1' sh "/data/blocks/$id.blk"
                if ($LASTEXITCODE -ne 0) { $present++ }
            }
        }
        return ($pending -eq '0' -and $present -eq 0)
    }
}

try {
    if ($ValidateFailures -and (-not $Secure -or $Resume)) { throw '-ValidateFailures exige -Secure y un proyecto NUEVO; promueve su standby' }
    Set-Location -LiteralPath $taskRepo
    & $taskDocker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine no esta accesible desde esta terminal' }
    $existing = & $taskDocker volume ls --filter ('label=com.docker.compose.project=' + $ProjectName) --format '{{.Name}}'
    if ($LASTEXITCODE -ne 0) { throw 'No pudo comprobarse el proyecto Docker; no se continua' }
    if ($existing -and -not $Resume) { throw 'El proyecto ya tiene volumenes. Usar -Resume solo para esta demo conocida; no se borrara nada.' }
    if ($Resume -and -not $existing) { throw 'No hay volumenes de esta demo para reanudar' }
    if ($Resume) {
        $role = & $taskDocker @taskCompose exec -T postgres-standby psql -U dfsha -d dfsha -tAc 'SELECT pg_is_in_recovery()'
        if ($LASTEXITCODE -ne 0 -or (($role | Out-String).Trim()) -ne 't') { throw 'No se reanuda un cluster con standby promovido o rol desconocido' }
    }
    $ports = @('CONTROLNODE1_PORT','CONTROLNODE2_PORT','DATANODE1_PORT','DATANODE2_PORT','DATANODE3_PORT','DATANODE4_PORT','POSTGRES_PORT','RABBITMQ_AMQP_PORT','RABBITMQ_MANAGEMENT_PORT')
    if (-not $Resume) { foreach ($portName in $ports) {
        $port = [int]$taskEnv[$portName]
        $probe = New-Object System.Net.Sockets.TcpClient
        try {
            $connect = $probe.ConnectAsync('127.0.0.1', $port)
            try { [void]$connect.Wait(300) } catch { }
            if ($probe.Connected) { throw "El puerto $port ya esta ocupado. No se detendra ningun servicio ajeno." }
        } finally { $probe.Dispose() }
    } }
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
        $file = Join-Path $taskSecrets $secret
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

    $taskStage = 'RF3'
    Invoke-DemoClient -Commands @($login, 'open /demo/texto.txt r', 'open /demo/texto.txt rw',
        'write h2 0 LISTO', 'read h2 0 5', 'cat /demo/texto.txt 0 5', 'close h2', 'read h1 0 5', 'close h1',
        'get /demo/texto.txt texto-actualizado.txt', 'exit') -LogName 'rf3.log'
    $changed = [IO.File]::ReadAllText((Join-Path $taskRun 'texto-actualizado.txt'))
    if ($changed -ne 'LISTO-DEMO-LOCAL: lectura parcial y bloqueos') { throw 'El reemplazo RF3 no conserva el resto del archivo' }
    $rf3log = [IO.File]::ReadAllText((Join-Path $taskRun 'rf3.log'))
    if ([regex]::Matches($rf3log, '(?m)dfsha:/> DFSHA\r?$').Count -ne 2) { throw 'No se verifico la instantanea anterior antes/despues del close' }
    Write-Host 'PASA: open/read/write/close publica una version nueva y conserva el resto del texto'

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
    Wait-DemoDeletion -BlockIds $ids
    Write-Host 'PASA: borrado publicado y ninguno de los tres bloques presente en los cuatro DataNodes'

    if ($Secure) {
        $taskStage = 'seguridad'
        Invoke-DemoCompose -Arguments @('exec','-T','postgres','sh','/scripts/check-tls.sh')
        $encryptedSql = Read-DemoSql "SELECT CASE WHEN count(*) > 0 AND bool_and(s.ssl) THEN 't' ELSE 'f' END FROM pg_stat_activity a JOIN pg_stat_ssl s USING(pid) WHERE a.datname = 'dfsha' AND a.usename = 'dfsha' AND a.client_addr IS NOT NULL"
        if ($encryptedSql -ne 't') { throw 'Las conexiones remotas de la aplicacion no estan todas cifradas' }
        $listeners = & $taskDocker @taskCompose exec -T rabbitmq rabbitmq-diagnostics -q listeners
        if ($LASTEXITCODE -ne 0) { throw 'No pudo verificarse el listener AMQP' }
        $listenerText = ($listeners | Out-String)
        [IO.File]::WriteAllText((Join-Path $taskRun 'rabbitmq-listeners.log'), $listenerText)
        if ($listenerText -notmatch 'amqp/ssl' -or $listenerText -match 'port: 5672,') { throw 'AMQP no es exclusivamente TLS' }
        Invoke-DemoClient -Commands @($login,'exit') -LogName 'rechazo-sin-tls.log' -WithoutTls -ExpectedError 'UNAVAILABLE'
        $otherUser = 'otro' + ([Guid]::NewGuid().ToString('N').Substring(0,12))
        $otherPassword = [Guid]::NewGuid().ToString('N')
        Invoke-DemoClient -Commands @("register $otherUser $otherPassword", "login $otherUser $otherPassword",
            'get /demo/texto.txt acceso-no-autorizado.txt','exit') -LogName 'aislamiento-usuarios.log' -ExpectedError 'NOT_FOUND'
        Invoke-DemoClient -Commands @($login,'lock /demo/texto.txt 12','exit') -LogName 'lock-primero.log'
        Invoke-DemoClient -Commands @($login,'lock /demo/texto.txt 12','exit') -LogName 'lock-rechazado.log' -ExpectedError 'FAILED_PRECONDITION'
        Start-Sleep -Seconds 13
        Invoke-DemoClient -Commands @($login,'lock /demo/texto.txt 12','unlock /demo/texto.txt','exit') -LogName 'lock-expirado.log'
        $taskFailureResults.Seguridad = 'SQL TLS verificado/rechazo plaintext; AMQP solo TLS; gRPC sin TLS rechazado; aislamiento y locks exclusivos/expiracion'
        Write-Host 'PASA: TLS SQL/AMQP/gRPC, aislamiento entre usuarios y lock exclusivo con expiracion'
    }

    if ($LoadClients -gt 0) {
        $taskStage = 'carga concurrente'
        $runs = @(); $clock = [Diagnostics.Stopwatch]::StartNew()
        for ($i = 1; $i -le $LoadClients; $i++) {
            $loadUser = 'carga' + ([Guid]::NewGuid().ToString('N').Substring(0,12))
            $loadPassword = [Guid]::NewGuid().ToString('N')
            $runs += Start-DemoClient -Commands @("register $loadUser $loadPassword", "login $loadUser $loadPassword",
                'put original.bin /carga.bin', "get /carga.bin carga-$i.bin", 'rm /carga.bin','exit') -LogName "carga-$i.log"
        }
        $loadFailed = $false
        foreach ($run in $runs) { try { Complete-DemoClient -Run $run } catch { Write-Host $_.Exception.Message; $loadFailed = $true } }
        $clock.Stop()
        if ($loadFailed) { throw 'Un cliente de carga fallo; revisar sus logs' }
        for ($i = 1; $i -le $LoadClients; $i++) {
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRun "carga-$i.bin")).Hash -ne $original) { throw 'Checksum incorrecto en carga concurrente' }
        }
        $taskLoadResults = [ordered]@{ Clientes=$LoadClients; BytesPorCliente=20971520; Segundos=[math]::Round($clock.Elapsed.TotalSeconds,3);
            MiBTransferidos=40*$LoadClients; MiBPorSegundo=[math]::Round(40*$LoadClients/$clock.Elapsed.TotalSeconds,3); ChecksumsCorrectos=$LoadClients }
        Write-Host ("PASA: $LoadClients clientes concurrentes; tiempo agregado {0:N2} s (incluye login y borrado)" -f $clock.Elapsed.TotalSeconds)
    }

    if ($ValidateFailures) {
        $taskStage = 'fallo de DataNode'
        Invoke-DemoClient -Commands @($login,'put original.bin /demo/ha.bin','exit') -LogName 'ha-subida.log'
        $haWhere = $fileWhere.Replace('/demo/archivo20.bin', '/demo/ha.bin')
        Wait-DemoCheck -Seconds 90 -Failure 'HA no obtuvo tres replicas iniciales' -Check {
            [int](Read-DemoSql "SELECT coalesce(min(n),0) FROM (SELECT count(*) FILTER (WHERE l.state = 'WRITTEN' AND d.status = 'ALIVE') n $haWhere GROUP BY b.block_id) x") -ge 3
        }
        $haIds = (Read-DemoSql "SELECT DISTINCT b.block_id $haWhere") -split '\s+'
        $victim = Read-DemoSql "SELECT d.node_name $haWhere AND b.block_index = 0 AND l.role = 'PRIMARY' LIMIT 1"
        if ($victim -notin @('datanode1','datanode2','datanode3','datanode4')) { throw 'Victima no pertenece a los DataNodes de esta demo' }
        Invoke-DemoCompose -Arguments @('stop',$victim)
        try {
            Invoke-DemoClient -Commands @($login,'get /demo/ha.bin ha-datanode.bin','exit') -LogName 'ha-datanode.log'
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRun 'ha-datanode.bin')).Hash -ne $original) { throw 'Descarga con DataNode caido corrupta' }
            Wait-DemoCheck -Seconds 60 -Failure 'DataNode no paso a DEAD' -Check { (Read-DemoSql "SELECT status FROM datanode WHERE node_name = '$victim'") -eq 'DEAD' }
            Wait-DemoCheck -Seconds 90 -Failure 'No se restauro factor tres tras la caida' -Check {
                [int](Read-DemoSql "SELECT coalesce(min(n),0) FROM (SELECT count(*) FILTER (WHERE l.state = 'WRITTEN' AND d.status = 'ALIVE') n $haWhere GROUP BY b.block_id) x") -ge 3
            }
        } finally { Invoke-DemoCompose -Arguments @('start',$victim) }
        $taskFailureResults.DataNode = 'Descarga con checksum correcto; DEAD detectado; factor tres repuesto'

        $taskStage = 'fallo de ControlNode'
        Invoke-DemoCompose -Arguments @('stop','controlnode1')
        try {
            Invoke-DemoClient -Commands @($login,'get /demo/ha.bin ha-controlnode.bin','exit') -LogName 'ha-controlnode.log'
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRun 'ha-controlnode.bin')).Hash -ne $original) { throw 'Descarga con ControlNode caido corrupta' }
        } finally { Invoke-DemoCompose -Arguments @('start','controlnode1') }
        $taskFailureResults.ControlNode = 'Cliente usa el otro ControlNode y conserva checksum'

        $taskStage = 'caida y recuperacion del broker'
        Invoke-DemoCompose -Arguments @('stop','rabbitmq')
        try {
            Invoke-DemoClient -Commands @($login,'rm /demo/ha.bin','exit') -LogName 'ha-broker-borrado.log'
            $haIdList = ($haIds | ForEach-Object { "'$_'::uuid" }) -join ','
            $pendingBroker = Read-DemoSql "SELECT count(*) FROM block_delete_outbox WHERE block_id IN ($haIdList) AND published_at IS NULL"
            if ([int]$pendingBroker -lt 1) { throw 'No quedo evidencia de tareas pendientes con el broker apagado' }
        } finally { Invoke-DemoCompose -Arguments @('start','rabbitmq') }
        Wait-DemoCheck -Seconds 90 -Failure 'No regresaron cuatro DataNodes ALIVE' -Check { (Read-DemoSql "SELECT count(*) FROM datanode WHERE status = 'ALIVE'") -eq '4' }
        Wait-DemoDeletion -BlockIds $haIds
        $taskFailureResults.Broker = 'Borrado logico durante caida; outbox pendiente observada; borrado fisico tras recuperacion'

        $taskStage = 'promocion PostgreSQL aislada'
        # La misma ubicacion de texto se verificara tras la promocion; esperar TODO el WAL previo.
        $lsn = Read-DemoSql 'SELECT pg_current_wal_lsn()'
        $taskDatabase = 'postgres-standby'
        Wait-DemoCheck -Seconds 60 -Failure 'Standby no alcanzo el WAL antes de promover' -Check {
            (Read-DemoSql "SELECT pg_last_wal_replay_lsn() >= '$lsn'::pg_lsn") -eq 't'
        }
        Invoke-DemoCompose -Arguments @('stop','postgres')
        Invoke-DemoCompose -Arguments @('exec','-T','postgres-standby','psql','-U','dfsha','-d','dfsha','-v','ON_ERROR_STOP=1','-c','SELECT pg_promote()')
        [IO.File]::WriteAllText((Join-Path $taskRun 'standby-promovido.txt'), 'NO reiniciar el primario antiguo. Proyecto: ' + $ProjectName)
        Wait-DemoCheck -Seconds 30 -Failure 'Standby no asumio rol primario' -Check { (Read-DemoSql 'SELECT pg_is_in_recovery()') -eq 'f' }
        # Una conexion fallida se invalida antes de repetir las operaciones idempotentes de lectura/login.
        Wait-DemoCheck -Seconds 60 -Failure 'Cliente no se recupero tras promover PostgreSQL' -Check {
            try { Invoke-DemoClient -Commands @($login,'get /demo/texto.txt ha-postgres.txt','exit') -LogName 'ha-postgres.log'; return $true } catch { return $false }
        }
        if ([IO.File]::ReadAllText((Join-Path $taskRun 'ha-postgres.txt')) -ne $changed) { throw 'Lectura tras promocion PostgreSQL incorrecta' }
        $taskFailureResults.PostgreSQL = 'WAL sincronizado antes de fallo controlado; promocion manual; lectura posterior verificada'
        Write-Host 'PASA: fallos controlados de DataNode, ControlNode, broker y PostgreSQL'
        Write-Warning 'El standby es ahora primario. NO volver a ejecutar up sobre este proyecto; el primario anterior queda detenido.'
    }
    $receipt = [ordered]@{ Fecha=(Get-Date -Format o); Proyecto=$ProjectName; Commit=((& git rev-parse HEAD) | Out-String).Trim();
        UsuarioDemo=$user; Bytes=20971520; SHA256Original=$original; SHA256Descarga=$download; Bloques=$ids;
        CopiasMinimas=[int]$copies; BorradoFisico=$true; RF3=$true; TLSCompleto=[bool]$Secure; JarSHA256=$jarHashes;
        Escenarios=$taskFailureResults; Carga=$taskLoadResults; StandbyPromovido=[bool]$ValidateFailures;
        Limites='Prueba local acotada; no nube, HA del host/broker, fallo abrupto sin sincronizar WAL, tolerancia bizantina ni rendimiento ilimitado. DLQ requiere recuperacion operativa.' }
    [IO.File]::WriteAllText((Join-Path $taskRun 'resultado.json'), ($receipt | ConvertTo-Json -Depth 7))
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
