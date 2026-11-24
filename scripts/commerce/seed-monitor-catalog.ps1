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
    number = 1; asset = 'cedar-24'; code = 'CEDAR-STUDIO-24'; brand = 'Cedar'; size = '24"'; resolution = '1920 × 1080'
    price = 699; name = 'Cedar Studio 24 Monitor'; nameZh = 'Cedar Studio 24 英寸显示器'
    brief = '24-inch FHD · 1920 × 1080'; briefZh = '24 英寸 FHD · 1920 × 1080'
    description = 'Compact 24-inch FHD desktop monitor with a matte panel and a rectangular stand.'
    descriptionZh = '适合紧凑桌面的 24 英寸 FHD 显示器，采用雾面屏与矩形底座。'
  },
  @{
    number = 2; asset = 'cedar-27'; code = 'CEDAR-STUDIO-27'; brand = 'Cedar'; size = '27"'; resolution = '2560 × 1440'
    price = 1099; name = 'Cedar Studio 27 Monitor'; nameZh = 'Cedar Studio 27 英寸显示器'
    brief = '27-inch QHD · 2560 × 1440'; briefZh = '27 英寸 QHD · 2560 × 1440'
    description = '27-inch QHD monitor with a slim bezel and a height-adjustable silver stand.'
    descriptionZh = '27 英寸 QHD 显示器，配有窄边框和可调节高度的银色支架。'
  },
  @{
    number = 3; asset = 'lumen-32'; code = 'LUMEN-VIEW-32'; brand = 'Lumen'; size = '32"'; resolution = '3840 × 2160'
    price = 1899; name = 'Lumen View 32 Monitor'; nameZh = 'Lumen View 32 英寸 4K 显示器'
    brief = '32-inch 4K UHD · 3840 × 2160'; briefZh = '32 英寸 4K UHD · 3840 × 2160'
    description = '32-inch 4K desktop monitor with a spacious flat panel and a silver T-shaped stand.'
    descriptionZh = '32 英寸 4K 平面显示器，采用银色 T 形支架，便于并排查看大画幅内容。'
  },
  @{
    number = 4; asset = 'lumen-34'; code = 'LUMEN-CURVE-34'; brand = 'Lumen'; size = '34"'; resolution = '3440 × 1440'
    price = 2399; name = 'Lumen Curve 34 Ultrawide Monitor'; nameZh = 'Lumen Curve 34 英寸曲面超宽显示器'
    brief = '34-inch 21:9 curved · 3440 × 1440'; briefZh = '34 英寸 21:9 曲面 · 3440 × 1440'
    description = '34-inch 21:9 curved ultrawide monitor with a matte charcoal enclosure and V-shaped stand.'
    descriptionZh = '34 英寸 21:9 曲面超宽屏，采用深灰色外壳和 V 形底座。'
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

$endpoint = '/api/merchant/catalog/v1/products'
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
    $null = Invoke-CatalogWrite "$productPath/status" Put @{
      expectedVersion = $product.businessVersion
      status = 'ACTIVE'
    }
  }
  $sku = Invoke-CatalogWrite "$productPath/skus" Post @{
    requestId = ('e48f673f-910d-405f-a051-{0:D12}' -f $entry.number)
    code = $entry.code
    specifications = @{ Brand = $entry.brand; 'Screen Size' = $entry.size; Resolution = $entry.resolution }
    unitPrice = $entry.price
    status = 'ACTIVE'
  }
  [pscustomobject]@{ ProductId = $product.productId; SkuId = $sku.skuId; Name = $entry.name; Image = $image }
}
