package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.PhotoCrops

// The crop screen in its three modes, with made-up data (PreviewData). See docs/previews.md.

private val crops = PreviewData.marketCrops

@PreviewTest
@Preview(name = "moments", device = PHONE, showBackground = true)
@Composable
fun CropMoments() = Screen {
    CropScreen(PreviewData.croppedPhoto, CropKind.MOMENTS, crops.moments!!, PhotoCrops(moments = crops.moments), PreviewData.SCREEN_RATIO, CropActions())
}

@PreviewTest
@Preview(name = "portrait", device = PHONE, showBackground = true)
@Composable
fun CropPortrait() = Screen {
    CropScreen(PreviewData.croppedPhoto, CropKind.PORTRAIT, crops.portrait!!, crops, PreviewData.SCREEN_RATIO, CropActions())
}

@PreviewTest
@Preview(name = "landscape", device = PHONE, showBackground = true)
@Composable
fun CropLandscape() = Screen {
    CropScreen(PreviewData.croppedPhoto, CropKind.LANDSCAPE, crops.landscape!!, crops, PreviewData.SCREEN_RATIO, CropActions())
}
