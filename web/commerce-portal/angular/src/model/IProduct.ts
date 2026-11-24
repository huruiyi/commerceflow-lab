import { CatalogSku } from './ICatalogSku';

export interface Product {
  id: number;
  merchantId?: number;
  name: string;
  brief: string;
  description: string;
  price: number;
  imageUrl: string;
  thumbnailUrl: string;
  averageRating: number;
  reviewCount: number;
  reviews: ProductReview[];
  skus?: CatalogSku[];
  minimumSkuPrice?: number | null;
  maximumSkuPrice?: number | null;
  monitorProfile?: CatalogMonitorProfile | null;
}

export interface CatalogMonitorProfile {
  brand: string;
  screenSizeInches: number;
  widthPixels: number;
  heightPixels: number;
  panel: string;
  refreshRateHz: number;
  ports: string[];
}

export interface ProductReview {
  id: number;
  rating: number;
  comment: string;
  reviewerName: string;
  createdAt: string;
}

export interface ProductReviewPage {
  reviews: ProductReview[];
  total: number;
  page: number;
  size: number;
}
