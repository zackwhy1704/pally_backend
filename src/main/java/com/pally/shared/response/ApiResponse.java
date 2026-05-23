package com.pally.shared.response;

/**
 * Generic API response wrapper used for all endpoints.
 *
 * @param data   the response payload (null on error)
 * @param error  error message (null on success)
 * @param status HTTP status code mirrored in the body for client convenience
 */
public record ApiResponse<T>(T data, String error, int status) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null, 200);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(data, null, 201);
    }

    public static <T> ApiResponse<T> error(String message, int status) {
        return new ApiResponse<>(null, message, status);
    }
}
