#requires -Version 7.0
param(
  [Parameter(Mandatory)][uri]$BaseUrl,
  [pscredential]$MerchantCredential
)

$ErrorActionPreference = 'Stop'
if (!$BaseUrl.IsAbsoluteUri -or !$BaseUrl.IsLoopback -or $BaseUrl.Scheme -notin 'http','https' -or
    $BaseUrl.AbsolutePath -ne '/' -or $BaseUrl.Query -or $BaseUrl.Fragment -or $BaseUrl.UserInfo) {
  throw 'BaseUrl must be the local storefront origin.'
}
$origin = $BaseUrl.GetLeftPart([UriPartial]::Authority)
if (!$MerchantCredential) {
  $MerchantCredential = [pscredential]::new('merchant@example.com', (ConvertTo-SecureString 'demo-password' -AsPlainText -Force))
}

$csrf = Invoke-RestMethod "$origin/api/auth/csrf" -SessionVariable session -TimeoutSec 15
$headers = @{ $csrf.headerName = $csrf.token }

function Invoke-CatalogWrite([string]$Path, [string]$Method, [hashtable]$Body) {
  Invoke-RestMethod "$origin$Path" -Method $Method -WebSession $session -Headers $headers -TimeoutSec 20 `
    -ContentType 'application/json' -Body (ConvertTo-Json -InputObject $Body -Depth 5 -Compress)
}

$null = Invoke-CatalogWrite '/api/auth/login' Post @{
  email = $MerchantCredential.UserName
  password = $MerchantCredential.GetNetworkCredential().Password
  rememberMe = $false
}
$products = @(
  @{
    number = 1; asset = 'cedar-24'; code = 'CEDAR-STUDIO-24'; brand = 'Cedar'; size = 24; width = 1920; height = 1080
    panel = 'IPS'; refresh = 75; ports = @('HDMI', 'DisplayPort')
    price = 699; name = 'Cedar Studio 24 Monitor'; nameZh = 'Cedar Studio 24 英寸显示器'
    brief = '24-inch FHD · 1920 × 1080'; briefZh = '24 英寸 FHD · 1920 × 1080'
    description = 'Compact 24-inch FHD desktop monitor with a matte panel and a rectangular stand.'
    descriptionZh = '适合紧凑桌面的 24 英寸 FHD 显示器，采用雾面屏与矩形底座。'
  },
  @{
    number = 2; asset = 'cedar-27'; code = 'CEDAR-STUDIO-27'; brand = 'Cedar'; size = 27; width = 2560; height = 1440
    panel = 'IPS'; refresh = 75; ports = @('HDMI', 'DisplayPort')
    price = 1099; name = 'Cedar Studio 27 Monitor'; nameZh = 'Cedar Studio 27 英寸显示器'
    brief = '27-inch QHD · 2560 × 1440'; briefZh = '27 英寸 QHD · 2560 × 1440'
    description = '27-inch QHD monitor with a slim bezel and a height-adjustable silver stand.'
    descriptionZh = '27 英寸 QHD 显示器，配有窄边框和可调节高度的银色支架。'
  },
  @{
    number = 3; asset = 'lumen-32'; code = 'LUMEN-VIEW-32'; brand = 'Lumen'; size = 32; width = 3840; height = 2160
    panel = 'IPS'; refresh = 60; ports = @('HDMI', 'DisplayPort', 'USB-C')
    price = 1899; name = 'Lumen View 32 Monitor'; nameZh = 'Lumen View 32 英寸 4K 显示器'
    brief = '32-inch 4K UHD · 3840 × 2160'; briefZh = '32 英寸 4K UHD · 3840 × 2160'
    description = '32-inch 4K desktop monitor with a spacious flat panel and a silver T-shaped stand.'
    descriptionZh = '32 英寸 4K 平面显示器，采用银色 T 形支架，便于并排查看大画幅内容。'
  },
  @{
    number = 4; asset = 'lumen-34'; code = 'LUMEN-CURVE-34'; brand = 'Lumen'; size = 34; width = 3440; height = 1440
    panel = 'VA'; refresh = 144; ports = @('HDMI', 'DisplayPort')
    price = 2399; name = 'Lumen Curve 34 Ultrawide Monitor'; nameZh = 'Lumen Curve 34 英寸曲面超宽显示器'
    brief = '34-inch 21:9 curved · 3440 × 1440'; briefZh = '34 英寸 21:9 曲面 · 3440 × 1440'
    description = '34-inch 21:9 curved ultrawide monitor with a matte charcoal enclosure and V-shaped stand.'
    descriptionZh = '34 英寸 21:9 曲面超宽屏，采用深灰色外壳和 V 形底座。'
  },
  @{
    number = 5; asset = 'cedar-27-play'; code = 'CEDAR-PLAY-27-ARM'; brand = 'Cedar'
    size = 27; width = 2560; height = 1440; panel = 'IPS'; refresh = 165; ports = @('HDMI', 'DisplayPort')
    price = 1199; standPrice = 1499; stock = 18; standStock = 7
    name = 'Cedar Play 27 QHD Monitor'; nameZh = 'Cedar Play 27 英寸 QHD 显示器'
    brief = '27-inch QHD · 165 Hz'; briefZh = '27 英寸 QHD · 165 Hz'
    description = '27-inch 165 Hz QHD IPS monitor with DisplayPort and HDMI. A monitor arm bundle is available.'
    descriptionZh = '27 英寸 165 Hz QHD IPS 显示器，支持 DisplayPort 与 HDMI，可选含支架套装。'
  },
  @{
    number = 6; asset = 'lumen-27-connect'; code = 'LUMEN-CONNECT-27'; brand = 'Lumen'
    size = 27; width = 2560; height = 1440; panel = 'IPS'; refresh = 100; ports = @('HDMI', 'DisplayPort', 'USB-C')
    price = 1399; stock = 12
    name = 'Lumen Connect 27 QHD Monitor'; nameZh = 'Lumen Connect 27 英寸 QHD 显示器'
    brief = '27-inch QHD · 100 Hz · USB-C'; briefZh = '27 英寸 QHD · 100 Hz · USB-C'
    description = '27-inch 100 Hz QHD IPS monitor with USB-C, DisplayPort and HDMI for desktop work.'
    descriptionZh = '27 英寸 100 Hz QHD IPS 办公显示器，支持 USB-C、DisplayPort 和 HDMI。'
  }
)

$store = Invoke-RestMethod "$origin/api/merchant/store" -WebSession $session -TimeoutSec 15
if ($store.status -ne 'ACTIVE') { throw 'The demo merchant store must be active.' }
foreach ($entry in $products) {
  $photo = Invoke-WebRequest "$origin/assets/catalog-monitors/$($entry.asset).webp" -Method Head -TimeoutSec 10
  if ($photo.StatusCode -ne 200 -or $photo.Headers['Content-Type'][0] -ne 'image/webp') {
    throw "Deploy the storefront with catalog-monitor photos before loading the demo: $($entry.asset)"
  }
}

function Get-MerchantResource([string]$Path) {
  Invoke-RestMethod "$origin$Path" -WebSession $session -TimeoutSec 15
}

function Initialize-MonitorStock([long]$StockProductId, [int]$Quantity) {
  $inventoryPath = "/api/merchant/inventory/$StockProductId"
  $deadline = [datetime]::UtcNow.AddSeconds(45)
  do {
    $balances = Get-MerchantResource '/api/merchant/inventory'
    $balance = $balances | Where-Object productId -eq $StockProductId
    if (!$balance) { Start-Sleep -Milliseconds 700 }
  } until ($balance -or [datetime]::UtcNow -ge $deadline)
  if (!$balance) { throw "Inventory did not receive catalog sales unit $StockProductId." }

  $reason = 'Monitor demo opening stock'
  $page = 0
  do {
    $movements = Get-MerchantResource "$inventoryPath/movements/page?size=100&page=$page"
    if ($movements.rows | Where-Object detail -eq $reason) { return }
    $page++
  } while ($page * $movements.size -lt $movements.total)
  $null = Invoke-CatalogWrite "$inventoryPath/adjustments" Post @{ delta = $Quantity; reason = $reason }
}

$endpoint = '/api/merchant/catalog/v1/products'
$initialized = [System.Threading.Mutex]::new($false, "CommerceFlow.MonitorCatalog.$($BaseUrl.Port)")
$hasLock = $false
try {
  try { $hasLock = $initialized.WaitOne([timespan]::FromSeconds(50)) }
  catch [System.Threading.AbandonedMutexException] { $hasLock = $true }
  if (!$hasLock) { throw 'Another monitor fixture import is running for this storefront.' }
  $existing = @{}
  foreach ($listing in (Get-MerchantResource $endpoint)) {
    $existing[[long]$listing.productId] = $listing
  }
  foreach ($entry in $products) {
    $image = "/assets/catalog-monitors/$($entry.asset).webp"
    $product = Invoke-CatalogWrite $endpoint Post @{
      requestId = ('f784543a-5123-432b-9030-{0:D12}' -f $entry.number)
      name = $entry.name
      description = $entry.description
      unitPrice = $entry.price
      imageUrl = $image
    }
    $productPath = "$endpoint/$($product.productId)"
    if ($product.lifecycleStatus -eq 'INACTIVE') {
      if ($product.businessVersion -eq 1) {
        $product = Invoke-CatalogWrite "$productPath/profile" Put @{
          expectedVersion = $product.businessVersion
          name = $entry.name
          brief = $entry.brief
          description = $entry.description
          imageUrl = $image
          thumbnailUrl = $image
        }
      }
      $product = Invoke-CatalogWrite "$productPath/translations/zh-CN" Put @{
        expectedVersion = $product.businessVersion
        name = $entry.nameZh
        brief = $entry.briefZh
        description = $entry.descriptionZh
      }
      $product = Invoke-CatalogWrite "$productPath/status" Put @{
        expectedVersion = $product.businessVersion
        status = 'ACTIVE'
      }
    }
    if (!$existing[[long]$product.productId].monitorProfile) {
      $null = Invoke-CatalogWrite "$productPath/monitor-profile" Put @{
        expectedVersion = $product.businessVersion
        profile = @{
          brand = $entry.brand; screenSizeInches = $entry.size; widthPixels = $entry.width; heightPixels = $entry.height
          panel = $entry.panel; refreshRateHz = $entry.refresh; ports = $entry.ports
        }
      }
    }
    $skus = Get-MerchantResource "$productPath/skus"
    $sku = $skus | Where-Object defaultSku
    if (!$sku) { throw "Missing default SKU for $($product.productId)." }
    if ($entry.standPrice -and $sku.specifications.package -ne 'standard') {
      $sku = Invoke-CatalogWrite "$productPath/skus/$($sku.skuId)" Put @{
        expectedVersion = $sku.businessVersion; code = $sku.code; unitPrice = $sku.unitPrice; status = $sku.status
        specifications = @{ package = 'standard' }
      }
    }
    if ($entry.stock) { Initialize-MonitorStock $sku.stockProductId $entry.stock }
    [pscustomobject]@{
      ProductId = $product.productId; SkuId = $sku.skuId; StockProductId = $sku.stockProductId
      Name = $entry.name; Package = $sku.specifications.package; Image = $image
    }

    if ($entry.standPrice) {
      $stand = Invoke-CatalogWrite "$productPath/skus" Post @{
        requestId = ('e48f673f-910d-405f-a051-{0:D12}' -f $entry.number)
        code = $entry.code
        specifications = @{ package = 'stand-bundle' }
        unitPrice = $entry.standPrice
        status = 'ACTIVE'
      }
      Initialize-MonitorStock $stand.stockProductId $entry.standStock
      [pscustomobject]@{
        ProductId = $product.productId; SkuId = $stand.skuId; StockProductId = $stand.stockProductId
        Name = $entry.name; Package = $stand.specifications.package; Image = $image
      }
    }
  }
} finally {
  if ($hasLock) { $initialized.ReleaseMutex() }
  $initialized.Dispose()
}
