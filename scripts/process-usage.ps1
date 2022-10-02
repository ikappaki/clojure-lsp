<#
.SYNOPSIS

Runs given EXECUTABLE with ARGS and samples its performance to a file.

.DESCRIPTION

Runs EXECUTABLE with ARGS and samples its performance with
`Get-Process` (of which see) at 50 ms intervals to
`process-usage-logs/<FileDataTime>.txt`, where `<FielDataTime>` is the
time the file is about to be created.

The files starts with a line of metadata in EDN format, followed by
the samples table whose columns are single space separated. The first
column is `timestamp(ms)` which holds the UNIX epoch in ms when the
sample was taken. The rest of the columns' header and values are taken
from the Get-Process output in the order they appear.

Line 1: {:exec "<EXECUTABLE>" :inteval-ms <sampling interval> :args "<EXECUTABLE's ARGS>"}
Line 2: Table header
Line 3..N: Table rows

Example Get-Process output:

Handles  NPM(K)    PM(K)      WS(K)     CPU(s)     Id  SI ProcessName
-------  ------    -----      -----     ------     --  -- -----------
    308      27    57740      51876       6.28   2032   1 firefox

After the EXECUTABLE process terminates, the following line is printed
to stdout:

:process-usage-output-path <path-to-samples-file>
#>

$interval_ms = 50
$timestamp_header = "timestamp(ms)"
$logdir = "process-usage-logs"

$exec, $exec_args = $args
if (!$exec)
{
    Write-Host "No EXECUTABLE supplied, exiting ..."
    Exit 55
}


New-Item -Path $logdir -ItemType directory -Force | Out-Null
$out = "${logdir}/$(get-date -Format ""FileDateTime"").txt"

$proc = if ($exec_args) {Start-Process $exec $exec_args -NoNewWindow -passthru}
	 else
	 {Start-Process $exec -NoNewWindow -passthru}

# Get UNIX epoch in ms
filter timestamp {"$([Math]::Round((Get-Date).ToFileTime()/10000)) $_"}

try {
    if (get-process -id $proc.id -ErrorAction SilentlyContinue)
    {
	"{:exec ""${exec}"" :interval-ms ${interval_ms} :args ""${exec_args}""}" | Add-content $out
	get-process -id $proc.id -ErrorAction SilentlyContinue| Format-Table | out-string |  % { $timestamp_header + " " + $_.split([Environment]::NewLine).trim()[2]} | Add-content $out
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
