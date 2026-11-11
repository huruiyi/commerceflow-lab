package lab.commerceflow.storefront.web.dto;import jakarta.validation.constraints.*;public record ReviewRequest(@Min(1)@Max(5)int rating,@NotBlank@Size(max=1000)String comment){}
