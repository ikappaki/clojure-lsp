$exec, $exec_args = $args
# echo $args.length "${exec_args}"
if (!$exec)
{
    Write-Host "No exec supplied, exiting ..."
    Exit 55
}


$interval_ms = 200

$logdir = "process-usage-logs"
New-Item -Path $logdir -ItemType directory -Force | Out-Null
$out = "${logdir}/$(get-date -Format ""FileDateTime"").txt"

$proc = if ($exec_args) {Start-Process $exec $exec_args -NoNewWindow -passthru}
	 else
	 {Start-Process $exec -NoNewWindow -passthru}

filter timestamp {"$([Math]::Round((Get-Date).ToFileTime()/10000)) $_"}
# filter timestamp {"$(Get-Date -Format o) $_"}

try {
    if (get-process -id $proc.id -ErrorAction SilentlyContinue)
    {
	"{:exec ""${exec}"" :interval-ms ${interval_ms} :args ""${exec_args}""}" | Add-content $out
	get-process -id $proc.id -ErrorAction SilentlyContinue| Format-Table | out-string |  % {$_.split([Environment]::NewLine).trim()[2]} | Add-content $out
    }
    while (get-process -id $proc.id -ErrorAction SilentlyContinue)
    {
	get-process -id $proc.id -ErrorAction SilentlyContinue | Format-Table -HideTableHeaders| out-string | % {$_.trim()} | timestamp | Add-content $out 
	start-sleep -milliseconds $interval_ms
    }
} finally {
    Stop-Process  $proc -ErrorAction SilentlyContinue
    Write-host "`n:process-usage-output-path ${out}"
}
