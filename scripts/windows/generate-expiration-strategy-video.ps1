param(
  [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $OutputPath) {
  $OutputPath = Join-Path $ProjectRoot 'docs\media\order-expiration-strategy-explainer.zh-CN.mp4'
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$OutputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('commerceflow-video-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Speech

$slides = @(
  [pscustomobject]@{
    Title = '订单超时策略：第八、九步详解'
    Subtitle = '配置结构与管理员页面设计'
    Body = "一个问题拆成两个维度：`n谁负责正常触发？`n谁负责异常兜底？"
    Code = ''
    Narration = '这一段解释订单超时策略中的第八和第九步。核心思想是，不要把数据库、Rabbit MQ 加数据库、Rocket MQ 加数据库都做成平级枚举。我们要把正常触发和异常补偿拆成两个独立配置。'
  },
  [pscustomobject]@{
    Title = '第八步：配置为什么分成两个维度'
    Subtitle = '主触发策略 + 数据库对账补偿'
    Body = "主触发策略：正常情况下，谁在到期时叫醒任务？`n`n数据库补偿：正常机制失效后，是否巡检并修复遗漏？"
    Code = "primary-trigger: RABBITMQ`nreconciliation:`n  enabled: true`n  grace-period: 2m`n  fixed-delay: 5m"
    Narration = '第八步的配置分成两个维度。主触发策略回答，正常情况下由谁在订单到期时叫醒任务。数据库对账补偿回答，如果主触发机制没有成功，是否允许数据库在宽限期以后巡检和修复。右侧配置表示 Rabbit MQ 负责正常路径，数据库负责异常路径。'
  },
  [pscustomobject]@{
    Title = '三种主触发方式，业务处理只有一套'
    Subtitle = '只替换闹钟，不复制关单状态机'
    Body = "DATABASE  · 定时扫描到期任务`nRABBITMQ · TTL / 延迟队列触发`nROCKETMQ · 原生延迟消息触发`n`n三者最终调用同一个 ExpireOrderService"
    Code = "Trigger Adapter`n      ↓`nAtomic Claim`n      ↓`nExpireOrderService`n      ↓`nCANCELLED + Outbox"
    Narration = '主触发方式可以是数据库、Rabbit MQ 或 Rocket MQ。但是它们只负责发出检查请求，最终必须调用同一个关单服务。订单锁定、关闭渠道支付、并发校验、状态变更和事件发布都只有一套实现。这样学习多种中间件，不会复制三套业务代码。'
  },
  [pscustomobject]@{
    Title = 'RabbitMQ + DB 到底怎么协作'
    Subtitle = 'MQ 正常触发，数据库延迟两分钟兜底'
    Body = "10:30  RabbitMQ 到期消息 → 正常关单`n`n10:32  数据库才检查仍未完成的任务`n`n谁先原子抢占成功，谁执行；另一方直接跳过。"
    Code = "UPDATE scheduled_order_tasks`nSET status = 'PROCESSING'`nWHERE id = ?`n  AND status = 'SCHEDULED'`n  AND due_at <= NOW();"
    Narration = '以 Rabbit MQ 加数据库为例。十点半由 Rabbit MQ 正常投递。数据库不会同时争抢，而是在十点三十二分，也就是超过两分钟宽限期以后，才检查任务是否仍未完成。如果消息积压导致两边同时到达，则通过条件更新原子抢占任务。只有更新成功的一方可以执行，另一方直接跳过。'
  },
  [pscustomobject]@{
    Title = '第九步：管理员页面不要提供五个混合选项'
    Subtitle = '两个控件表达两个独立问题'
    Body = "① 订单超时主触发方式`n   ○ 数据库扫描`n   ● RabbitMQ 延迟消息`n   ○ RocketMQ 延迟消息`n`n② 安全补偿`n   ☑ 启用数据库对账兜底`n   宽限期：2 分钟    对账间隔：5 分钟"
    Code = ''
    Narration = '第九步的管理员页面也按两个维度设计。第一个区域用单选框选择唯一的主触发策略。第二个区域用独立开关决定是否启用数据库兜底，并配置宽限期和对账间隔。这样管理员能清楚区分正常路径和异常恢复，不需要理解 Rabbit MQ 加数据库这种组合名称。'
  },
  [pscustomobject]@{
    Title = '配置变更只影响新任务'
    Subtitle = '已创建任务保存策略快照'
    Body = "旧任务：RABBITMQ · policyVersion 3`n新任务：ROCKETMQ · policyVersion 4`n`n旧任务不会因为管理员修改默认配置而中途换队列。"
    Code = "primary_strategy = RABBITMQ`nreconciliation_enabled = true`npolicy_version = 3`ntriggered_by = DB_RECONCILIATION"
    Narration = '管理员修改配置以后，只影响新创建的定时任务。已有任务保存主策略和策略版本快照，继续使用原来的消息队列。数据库如果最终完成补偿，只记录实际触发来源是数据库对账，主策略仍然是 Rabbit MQ。这不是策略迁移，而是故障恢复。'
  },
  [pscustomobject]@{
    Title = '最终记忆方式'
    Subtitle = '主策略负责准时，数据库补偿负责不遗漏'
    Body = "primary-trigger`n= 正常情况下谁负责叫醒任务`n`nreconciliation-enabled`n= 正常机制失效后是否允许数据库补救"
    Code = "推荐生产学习配置`n`nRABBITMQ`n+`n数据库对账兜底"
    Narration = '最后只需要记住，主触发策略负责准时，数据库对账补偿负责不遗漏。推荐的生产学习配置是 Rabbit MQ 负责正常延迟触发，数据库负责异常兜底。无论从哪个入口触发，最终都由同一个幂等关单服务处理。'
  }
)

function Draw-Text {
  param($Graphics, [string]$Text, $Font, $Brush, [float]$X, [float]$Y, [float]$Width, [float]$Height)
  $format = [System.Drawing.StringFormat]::new()
  $format.Trimming = [System.Drawing.StringTrimming]::EllipsisWord
  $format.FormatFlags = [System.Drawing.StringFormatFlags]::LineLimit
  $Graphics.DrawString($Text, $Font, $Brush, [System.Drawing.RectangleF]::new($X, $Y, $Width, $Height), $format)
  $format.Dispose()
}

function Render-Slide {
  param($Slide, [int]$Index, [int]$Total, [string]$Path)
  $bitmap = [System.Drawing.Bitmap]::new(1920, 1080)
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

  $background = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
    [System.Drawing.Rectangle]::new(0, 0, 1920, 1080),
    [System.Drawing.Color]::FromArgb(12, 24, 48),
    [System.Drawing.Color]::FromArgb(13, 73, 75),
    20
  )
  $graphics.FillRectangle($background, 0, 0, 1920, 1080)
  $accent = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(45, 212, 191))
  $graphics.FillRectangle($accent, 0, 0, 1920, 14)

  $titleFont = [System.Drawing.Font]::new('SimHei', 48, [System.Drawing.FontStyle]::Bold)
  $subtitleFont = [System.Drawing.Font]::new('SimHei', 25, [System.Drawing.FontStyle]::Regular)
  $bodyFont = [System.Drawing.Font]::new('SimHei', 31, [System.Drawing.FontStyle]::Regular)
  $codeFont = [System.Drawing.Font]::new('Consolas', 28, [System.Drawing.FontStyle]::Regular)
  $smallFont = [System.Drawing.Font]::new('SimHei', 19, [System.Drawing.FontStyle]::Regular)
  $white = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
  $muted = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(191, 219, 254))
  $dark = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(15, 23, 42))
  $panel = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(224, 255, 255, 255))
  $codeBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(220, 252, 231))

  Draw-Text $graphics $Slide.Title $titleFont $white 100 70 1650 90
  Draw-Text $graphics $Slide.Subtitle $subtitleFont $muted 105 165 1650 55
  Draw-Text $graphics (('{0:00}/{1:00}' -f ($Index + 1), $Total)) $smallFont $muted 1730 85 120 50

  if ($Slide.Code) {
    $graphics.FillRectangle($panel, 90, 260, 1020, 660)
    $graphics.FillRectangle($dark, 1150, 260, 680, 660)
    Draw-Text $graphics $Slide.Body $bodyFont $dark 135 310 920 560
    Draw-Text $graphics $Slide.Code $codeFont $codeBrush 1200 320 580 540
  } else {
    $graphics.FillRectangle($panel, 150, 260, 1620, 660)
    Draw-Text $graphics $Slide.Body $bodyFont $dark 220 320 1480 540
  }

  Draw-Text $graphics 'CommerceFlow Lab · 订单超时策略学习' $smallFont $muted 100 1000 900 40

  $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
  foreach ($object in @($graphics, $bitmap, $background, $accent, $titleFont, $subtitleFont, $bodyFont, $codeFont, $smallFont, $white, $muted, $dark, $panel, $codeBrush)) {
    if ($object) { $object.Dispose() }
  }
}

try {
  $voice = [System.Speech.Synthesis.SpeechSynthesizer]::new()
  $voice.SelectVoice('Microsoft Huihui Desktop')
  $voice.Rate = 0
  $clips = @()
  for ($index = 0; $index -lt $slides.Count; $index++) {
    $image = Join-Path $work ("slide-{0:00}.png" -f $index)
    $audio = Join-Path $work ("slide-{0:00}.wav" -f $index)
    $clip = Join-Path $work ("clip-{0:00}.mp4" -f $index)
    Render-Slide $slides[$index] $index $slides.Count $image
    $voice.SetOutputToWaveFile($audio)
    $voice.Speak($slides[$index].Narration)
    $voice.SetOutputToNull()
    & $ffmpeg -hide_banner -loglevel error -y -loop 1 -framerate 30 -i $image -i $audio -vf 'scale=1920:1080,format=yuv420p' -c:v libx264 -preset medium -tune stillimage -c:a aac -b:a 160k -shortest -movflags +faststart $clip
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed while creating clip $index" }
    $clips += $clip
  }
  $voice.Dispose()

  $concat = Join-Path $work 'concat.txt'
  $clips | ForEach-Object { "file '$($_.Replace("'", "''"))'" } | Set-Content -Encoding utf8 $concat
  & $ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i $concat -c copy -movflags +faststart $OutputPath
  if ($LASTEXITCODE -ne 0) { throw 'ffmpeg failed while concatenating the video' }
  Write-Host $OutputPath
}
finally {
  if ($voice) { $voice.Dispose() }
  Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
