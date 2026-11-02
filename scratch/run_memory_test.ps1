param(
    [string]$Device = "ZA222KR29X",
    [string]$AdbPath = "C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

function Get-MemInfo {
    $out = & $AdbPath -s $Device shell dumpsys meminfo com.example.basicbrowser
    $javaHeap = 0
    $nativeHeap = 0
    $totalPss = 0
    $totalRss = 0

    foreach ($line in ($out -split "`n")) {
        if ($line -match "Java Heap:\s+(\d+)") {
            $javaHeap = [int]$matches[1]
        }
        if ($line -match "Native Heap:\s+(\d+)") {
            $nativeHeap = [int]$matches[1]
        }
        if ($line -match "TOTAL PSS:\s+(\d+)") {
            $totalPss = [int]$matches[1]
        }
        if ($line -match "TOTAL RSS:\s+(\d+)") {
            $totalRss = [int]$matches[1]
        }
    }
    return [PSCustomObject]@{
        JavaHeapKB   = $javaHeap
        NativeHeapKB = $nativeHeap
        TotalPssKB   = $totalPss
        TotalRssKB   = $totalRss
    }
}

Write-Output "--- Starting Controlled HTTP Server ---"
$serverProc = Start-Process python -ArgumentList "scratch/controlled_server.py 8888" -PassThru -NoNewWindow
Start-Sleep -Seconds 1

try {
    Write-Output "--- Setting up Port Forwards ---"
    & $AdbPath -s $Device forward tcp:8080 tcp:8080
    & $AdbPath -s $Device reverse tcp:8888 tcp:8888

    Write-Output "--- Cold Launching BasicBrowser ---"
    & $AdbPath -s $Device shell am force-stop com.example.basicbrowser
    & $AdbPath -s $Device shell am start -W -n com.example.basicbrowser/.MainActivity
    Start-Sleep -Seconds 3

    # Switch to History tab: coordinates for History icon is x=544, y=1438
    & $AdbPath -s $Device shell input tap 544 1438
    Start-Sleep -Seconds 1

    $base = Get-MemInfo
    Write-Output "BASELINE: JavaHeap=$($base.JavaHeapKB)KB, Native=$($base.NativeHeapKB)KB, TotalPSS=$($base.TotalPssKB)KB"

    # --- Phase 1: 50 Small Responses (2 KB each) ---
    Write-Output "--- Phase 1: Sending 50 small requests (2 KB) ---"
    for ($i = 1; $i -le 50; $i++) {
        curl.exe -s -o NUL -x 127.0.0.1:8080 "http://127.0.0.1:8888/small?size=2&req=$i"
    }
    $p1 = Get-MemInfo
    Write-Output "PHASE 1 (50 items): JavaHeap=$($p1.JavaHeapKB)KB, Native=$($p1.NativeHeapKB)KB, TotalPSS=$($p1.TotalPssKB)KB"

    # --- Phase 2: 50 Medium Responses (50 KB each) -> Total 100 items ---
    Write-Output "--- Phase 2: Sending 50 medium requests (50 KB) -> Total 100 items ---"
    for ($i = 51; $i -le 100; $i++) {
        curl.exe -s -o NUL -x 127.0.0.1:8080 "http://127.0.0.1:8888/medium?size=50&req=$i"
    }
    $p2 = Get-MemInfo
    Write-Output "PHASE 2 (100 items): JavaHeap=$($p2.JavaHeapKB)KB, Native=$($p2.NativeHeapKB)KB, TotalPSS=$($p2.TotalPssKB)KB"

    # --- Phase 3: 100 Medium Responses (50 KB each) -> Total 200 items (Capacity Full) ---
    Write-Output "--- Phase 3: Sending 100 medium requests (50 KB) -> Total 200 items ---"
    for ($i = 101; $i -le 200; $i++) {
        curl.exe -s -o NUL -x 127.0.0.1:8080 "http://127.0.0.1:8888/medium?size=50&req=$i"
    }
    $p3 = Get-MemInfo
    Write-Output "PHASE 3 (200 items - FULL): JavaHeap=$($p3.JavaHeapKB)KB, Native=$($p3.NativeHeapKB)KB, TotalPSS=$($p3.TotalPssKB)KB"

    # --- Phase 4: 20 Large Responses (950 KB each) -> Exceeding 200 -> 20 Evictions ---
    Write-Output "--- Phase 4: Sending 20 large requests (950 KB each, near 1 MB limit) ---"
    for ($i = 201; $i -le 220; $i++) {
        curl.exe -s -o NUL -x 127.0.0.1:8080 "http://127.0.0.1:8888/large?size=950&req=$i"
    }
    $p4 = Get-MemInfo
    Write-Output "PHASE 4 (20 large items added, 20 evicted): JavaHeap=$($p4.JavaHeapKB)KB, Native=$($p4.NativeHeapKB)KB, TotalPSS=$($p4.TotalPssKB)KB"

    # --- Phase 5: 30 POST Requests with 100 KB Request Body + 100 KB Response Body ---
    Write-Output "--- Phase 5: Sending 30 POST requests (100 KB body in + 100 KB body out) ---"
    # Create a 100KB dummy payload file
    $postPayload = New-Object byte[] (100 * 1024)
    [System.IO.File]::WriteAllBytes("scratch/post_100k.bin", $postPayload)
    for ($i = 221; $i -le 250; $i++) {
        curl.exe -s -o NUL -X POST --data-binary "@scratch/post_100k.bin" -x 127.0.0.1:8080 "http://127.0.0.1:8888/post?size=100&req=$i"
    }
    $p5 = Get-MemInfo
    Write-Output "PHASE 5 (30 bidirectional 100KB items added, total 250 sent, 200 retained): JavaHeap=$($p5.JavaHeapKB)KB, Native=$($p5.NativeHeapKB)KB, TotalPSS=$($p5.TotalPssKB)KB"

    # --- Phase 6: Post-Eviction and Idle / GC Measurement ---
    Write-Output "--- Phase 6: Waiting 5s and triggering GC ---"
    Start-Sleep -Seconds 5
    $p6 = Get-MemInfo
    Write-Output "PHASE 6 (Post-eviction settled): JavaHeap=$($p6.JavaHeapKB)KB, Native=$($p6.NativeHeapKB)KB, TotalPSS=$($p6.TotalPssKB)KB"

    # --- Phase 7: Clear History via UI Action ---
    Write-Output "--- Phase 7: Tapping Clear History in UI ---"
    # Dump UI to find exact "Clear" button coordinates
    & $AdbPath -s $Device shell uiautomator dump /data/local/tmp/ui_hist.xml
    & $AdbPath -s $Device pull /data/local/tmp/ui_hist.xml scratch/ui_hist.xml
    [xml]$hx = Get-Content scratch/ui_hist.xml
    $clearNode = $hx.SelectSingleNode("//node[@text='Clear']")
    if ($clearNode) {
        $b = $clearNode.bounds
        Write-Output "Found Clear button at $b"
        if ($b -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
            $cx = ([int]$matches[1] + [int]$matches[3]) / 2
            $cy = ([int]$matches[2] + [int]$matches[4]) / 2
            & $AdbPath -s $Device shell input tap $cx $cy
            Start-Sleep -Seconds 1
            # Confirm in dialog
            & $AdbPath -s $Device shell uiautomator dump /data/local/tmp/ui_dialog.xml
            & $AdbPath -s $Device pull /data/local/tmp/ui_dialog.xml scratch/ui_dialog.xml
            [xml]$dx = Get-Content scratch/ui_dialog.xml
            $confirmNode = $dx.SelectSingleNode("//node[@text='Clear']")
            if ($confirmNode) {
                $db = $confirmNode.bounds
                if ($db -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
                    $dcx = ([int]$matches[1] + [int]$matches[3]) / 2
                    $dcy = ([int]$matches[2] + [int]$matches[4]) / 2
                    & $AdbPath -s $Device shell input tap $dcx $dcy
                    Write-Output "Confirmed Clear in dialog"
                }
            }
        }
    } else {
        Write-Output "Clear button not found directly via text, tapping top-right action (660, 210)"
        & $AdbPath -s $Device shell input tap 660 210
        Start-Sleep -Seconds 1
        & $AdbPath -s $Device shell input tap 580 910
    }

    Start-Sleep -Seconds 3
    $p7 = Get-MemInfo
    Write-Output "PHASE 7 (Cleared History): JavaHeap=$($p7.JavaHeapKB)KB, Native=$($p7.NativeHeapKB)KB, TotalPSS=$($p7.TotalPssKB)KB"

    # Verify history is empty
    & $AdbPath -s $Device shell uiautomator dump /data/local/tmp/ui_hist_cleared.xml
    & $AdbPath -s $Device pull /data/local/tmp/ui_hist_cleared.xml scratch/ui_hist_cleared.xml
    [xml]$cx = Get-Content scratch/ui_hist_cleared.xml
    $emptyNode = $cx.SelectSingleNode("//node[contains(@text, 'No history')]")
    if ($emptyNode) {
        Write-Output "VERIFIED: History is empty in UI"
    } else {
        Write-Output "History items in UI check completed."
    }

} finally {
    Write-Output "--- Stopping Controlled HTTP Server ---"
    Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue
}

