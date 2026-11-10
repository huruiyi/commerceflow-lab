export interface PurchaseOrderItem { id:number; productId:number; productName:string; thumbnailUrl:string; unitPrice:number; quantity:number; subtotal:number; }
export interface OrderAddress { recipientName:string; phone:string; countryCode:string; stateProvince:string; city:string; district:string; addressLine1:string; addressLine2:string; postalCode:string; label:string; }
export interface OrderPayment { paymentNumber:string; status:string; failureReason:string; amount:number; createdAt:string; }
export interface PurchaseOrder { id:number; orderNumber:string; status:string; totalAmount:number; currency:string; createdAt:string; items:PurchaseOrderItem[]; address:OrderAddress; payment?:OrderPayment; }
