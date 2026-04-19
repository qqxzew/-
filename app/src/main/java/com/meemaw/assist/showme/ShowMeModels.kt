package com.meemaw.assist.showme

data class ShowMeVisionResponse(
    val message: String,
    val annotationInstruction: String? = null
)

data class ShowMeAnalysisResult(
    val message: String,
    val annotatedImagePath: String? = null
)

data class PendingShowMeCapture(
    val filePath: String,
    val uriString: String
)