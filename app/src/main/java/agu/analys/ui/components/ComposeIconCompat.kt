package agu.analys.ui.components

import androidx.compose.material3.Icon as Material3Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Compatibility wrapper for AISignalCard's existing Icon call sites.
 * The parameter order matches Material3 Icon usage so positional modifier
 * arguments and named tint arguments compile consistently.
 */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    Material3Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}
