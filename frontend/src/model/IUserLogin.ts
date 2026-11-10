export interface LoginResponse {
  user: UserProfile;
}

export interface UserProfile {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  role: string;
}
