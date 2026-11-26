# D0 手动运行命令

基本流程与导入约束见 [D0 设计](../design/MODEL_INTEGRATION.md#22-d0-手动运行)。跨平台验证见 [POC-06](../validation/POC-06.md)。

## macOS / Linux Bash

在保存请求 JSON 的目录启动 Bash，核对文件名、替换 Model ID 和 Base URL。以下示例针对无鉴权网关，需鉴权时在提示处输入；使用 curl 7.76.0 或更新版本（--fail-with-body）。每次在当前目录创建独有的结果目录，只有 curl 成功且 HTTP 为 2xx 才生成 response.json。

```bash
(
  set +x
  set -o pipefail
  base='http://127.0.0.1:11434/v1'
  request='request.json'
  [[ -r "$request" ]] || { printf 'Request file missing\n' >&2; exit 1; }
  run_dir=$(mktemp -d ./promptlab-run.XXXXXXXX) || exit 1
  trap 'rm -f -- "$run_dir/body.tmp"' EXIT

  unset key
  IFS= read -r -s -p 'API Key (Enter for no auth): ' key || exit 1
  [[ $key != *$'\r'* ]] || { printf 'Invalid API Key characters\n' >&2; exit 1; }
  printf '\n'
  args=(--fail-with-body --silent --show-error --connect-timeout 5
        --max-time 180 --max-filesize 1048576
        -H 'Content-Type: application/json' --data-binary "@$request"
        -o "$run_dir/body.tmp" -w '%{http_code}')
  if [[ -n "$key" ]]; then
    status=$(printf 'Authorization: Bearer %s\n' "$key" |
      curl -q -H @- "${args[@]}" "${base%/}/chat/completions")
    code=$?
  else
    status=$(curl -q "${args[@]}" "${base%/}/chat/completions")
    code=$?
  fi
  unset key
  if [[ "$code" -ne 0 || "$status" != 2[0-9][0-9] ]]; then
    printf 'Request failed (curl %s, HTTP %s)\n' "$code" "$status" >&2
    exit 1
  fi
  mv -- "$run_dir/body.tmp" "$run_dir/response.json" || exit 1
  printf 'Import this file: %s/response.json\n' "$run_dir"
)
```

无鉴权时可直接回车，脚本只发 Content-Type 请求头。只用无鉴权网关时，可删除从 unset key 到 printf 换行的输入部分，再用以下两行替换 if/else 鉴权分支以及其后的 unset key。保留 args 数组和文件、HTTP 状态检查。

```bash
status=$(curl -q "${args[@]}" "${base%/}/chat/completions")
code=$?
```

## Windows PowerShell

在 PowerShell 中进入请求文件目录，替换文件名、Model ID 和 Base URL。使用 curl.exe 7.76.0 或更新版本，避免 PowerShell 的 curl 别名。用隐蔽输入读取 Key，通过 stdin 送入请求头；无鉴权直接回车。

```powershell
& {
    $base = 'http://127.0.0.1:11434/v1'
    $request = 'request.json'
    if (-not (Test-Path -LiteralPath $request -PathType Leaf)) {
        throw 'Request file missing'
    }
    $runDir = Join-Path (Get-Location).Path ('promptlab-run-' + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $runDir -ErrorAction Stop | Out-Null
    $tempFile = Join-Path $runDir 'body.tmp'
    $response = Join-Path $runDir 'response.json'
    $curlArgs = @('--fail-with-body', '--silent', '--show-error',
                  '--connect-timeout', '5', '--max-time', '180',
                  '--max-filesize', '1048576', '-H', 'Content-Type: application/json',
                  '--data-binary', "@$request", '-o', $tempFile, '-w', '%{http_code}',
                  ($base.TrimEnd('/') + '/chat/completions'))
    $secret = Read-Host 'API Key (Enter for no auth)' -AsSecureString
    try {
        if ($secret.Length -eq 0) {
            $status = & curl.exe -q @curlArgs
        } else {
            $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
            try {
                $key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
                if ($key -notmatch '^[\x21-\x7E]+$') { throw 'Invalid API Key characters' }
                $status = "Authorization: Bearer $key" | & curl.exe -q -H '@-' @curlArgs
            } finally {
                Remove-Variable key -ErrorAction SilentlyContinue
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
            }
        }
        $code = $LASTEXITCODE
        if ($code -ne 0 -or $status -notmatch '^2[0-9][0-9]$') {
            throw "Request failed (curl $code, HTTP $status)"
        }
        Move-Item -LiteralPath $tempFile -Destination $response -ErrorAction Stop
        "Import this file: $response"
    } finally {
        if ($null -ne $secret) { $secret.Dispose() }
        if (Test-Path -LiteralPath $tempFile) { Remove-Item -LiteralPath $tempFile }
    }
}
```

只用无鉴权网关时，可直接将密钥读取和 try 内的鉴权分支改为调用 `$status = & curl.exe -q @curlArgs`，并删除 finally 中清理 `$secret` 的一行，保留状态检查及临时文件清理。两个示例都把 -q 放在首个参数位置，保持 TLS 校验，不跟随重定向。2xx 仍需经过网站的响应正文检查，只有本次打印的文件可导入。请求与响应可能含私人信息，按需清理，避免分享。curl 的大小限制随版本存在差异，浏览器导入仍独立执行字节上限。其他平台与工具需用[验证矩阵](../TESTING.md)验证。 [curl 官方参数说明](https://curl.se/docs/manpage.html#--fail-with-body)
