package com.platform.order.dto;

import jakarta.validation.constraints.NotBlank;

public record ShippingAddressRequest(
        @NotBlank(message = "Street address must not be blank")
        String street,

        @NotBlank(message = "City must not be blank")
        String city,

        @NotBlank(message = "State must not be blank")
        String state,

        @NotBlank(message = "Postal code must not be blank")
        String postalCode,

        @NotBlank(message = "Country must not be blank")
        String country
) {}
