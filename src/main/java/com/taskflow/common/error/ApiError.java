package com.taskflow.common.error;

import java.util.Map;

public record ApiError(int status, String message, String path, Map<String, String> fieldErrors) {
}
