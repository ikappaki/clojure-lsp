$exec, $exec_args = $args
# echo $args.length "${exec_args}"
$out = "123.txt"
$proc = if ($exec_args) {Start-Process $exec $exec_args -NoNewWindow -passthru}
	 else
	 {Start-Process $exec -NoNewWindow -passthru}
	


try {
    if (get-process -id $proc.id -ErrorAction SilentlyContinue)
    {
	get-process -id $proc.id| Format-Table | out-string |  % {$_.split([Environment]::NewLine).trim()[2]} | Add-content $out
    }
    while (get-process -id $proc.id -ErrorAction SilentlyContinue)
    {
	get-process -id $proc.id -ErrorAction SilentlyContinue | Format-Table -HideTableHeaders| out-string | % {$_.trim()} | Add-content $out 
	# get-process -id $proc.id| select-object @{l='Private Memory (MB)'; e={$_.privatememorysize64 / 1mb}}| Format-Table -HideTableHeaders | out-string | Add-content "123.txt"
	start-sleep 0.5
    }
} finally {
    Stop-Process  $proc -ErrorAction SilentlyContinue
}

# # get-process -id $id | select-object @{l='Private Memory (MB)'; e={$_.privatememorysize64 / 1mb}}
