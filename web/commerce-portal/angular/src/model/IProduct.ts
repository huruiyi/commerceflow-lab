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
