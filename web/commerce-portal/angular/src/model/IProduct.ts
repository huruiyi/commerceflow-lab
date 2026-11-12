export interface Product {
  id: number;
  name: string;
  brief: string;
  description: string;
  price: number;
  imageUrl: string;
  thumbnailUrl: string;
  averageRating: number;
  reviewCount: number;
  reviews: { id: number; rating: number; comment: string; reviewerName: string; createdAt: string }[];
}
