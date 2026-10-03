package com.simorgh.mac.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.simorgh.mac.ui.components.drawBlob
import com.simorgh.mac.ui.components.rememberBlob
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.simorgh.mac.str.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simorgh.mac.ui.components.LightningBadge
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.simorgh.mac.R
import com.simorgh.mac.model.ConnectionProfile
import com.simorgh.mac.ui.icons.ZeroIcons
import com.simorgh.mac.ui.theme.LocalReducedMotion
import com.simorgh.mac.ui.theme.ZeroMotion
import com.simorgh.mac.ui.theme.ZeroTheme

private val CardShape = RoundedCornerShape(18.dp)

/** Normal / Fast / Gaming, with one line under it saying what the choice does. */
@Composable
fun ProfileSelector(
    selected: ConnectionProfile,
    onSelect: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZeroTheme.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // One blob for the whole row: it sits behind the chosen card and, when
        // another is chosen, stretches over to it.
        val blob = rememberBlob(ConnectionProfile.entries.indexOf(selected))
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val gap = 10.dp.toPx()
                    val n = ConnectionProfile.entries.size
                    val cell = (size.width - gap * (n - 1)) / n
                    val corner = 18.dp.toPx()
                    val edge = 1.dp.toPx()
                    for (i in 0 until n) {
                        val x = i * (cell + gap)
                        drawRoundRect(c.surface, Offset(x, 0f), Size(cell, size.height), CornerRadius(corner))
                        drawRoundRect(c.hairline, Offset(x, edge / 2), Size(cell, size.height - edge), CornerRadius(corner), style = Stroke(edge))
                    }
                    drawBlob(
                        blob = blob,
                        count = n,
                        area = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                        gap = gap,
                        color = c.accent.copy(alpha = if (c.isDark) 0.16f else 0.13f),
                        // The cards flow right to left in Persian and Arabic; the blob has to follow.
                        mirrored = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl,
                        outline = c.accent,
                        corner = corner,
                    )
                }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ConnectionProfile.entries.forEach { p ->
                ProfileCard(
                    profile = p,
                    selected = p == selected,
                    onClick = { onSelect(p) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val reduced = LocalReducedMotion.current
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                fadeIn(tween(ZeroMotion.ms(if (reduced) 150 else 220))) togetherWith fadeOut(tween(ZeroMotion.ms(if (reduced) 150 else 120)))
            },
            label = "profileHint",
        ) { p ->
            Text(
                stringResource(p.hint()),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ConnectionProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = ZeroTheme.colors
    val reduced = LocalReducedMotion.current
        val gamingLive = selected && profile == ConnectionProfile.Gaming
    val clock = rememberAmbientClock(gamingLive && !reduced)

    val content by animateColorAsState(
        if (selected) c.accent else c.muted,
        tween(ZeroMotion.ms(if (reduced) 150 else 260)),
        label = "profileContent",
    )
    val press = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected && !reduced) {
            pop.snapTo(0.92f)
            pop.animateTo(1f, ZeroMotion.expressive())
        }
    }
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction, reduced) {
        interaction.interactions.collect { i ->
            if (reduced) return@collect
            when (i) {
                is PressInteraction.Press -> press.animateTo(0.95f, ZeroMotion.snappy())
                is PressInteraction.Release, is PressInteraction.Cancel -> press.animateTo(1f, ZeroMotion.expressive())
            }
        }
    }

    Column(
        modifier
            .heightIn(min = 72.dp)
            .graphicsLayer {
                val s = press.value * pop.value
                scaleX = s; scaleY = s
            }
            .clip(CardShape)
            .then(
                if (gamingLive) {
                    // A neon edge that chases itself around the card.
                    Modifier.drawWithCache {
                        val stroke = Stroke(2.dp.toPx())
                        val corner = CornerRadius(18.dp.toPx())
                        val brush = Brush.sweepGradient(
                            0f to c.accentHot,
                            0.33f to c.accent,
                            0.66f to c.accentBright,
                            1f to c.accentHot,
                        )
                        onDrawWithContent {
                            drawContent()
                            val angle = if (reduced) 0f else clock.floatValue / 8f
                            // Rotating a sweep gradient inside the rounded rect's outline.
                            val path = androidx.compose.ui.graphics.Path().apply {
                                addRoundRect(
                                    androidx.compose.ui.geometry.RoundRect(
                                        left = stroke.width / 2f,
                                        top = stroke.width / 2f,
                                        right = size.width - stroke.width / 2f,
                                        bottom = size.height - stroke.width / 2f,
                                        cornerRadius = corner,
                                    ),
                                )
                            }
                            clipPath(path, clipOp = ClipOp.Intersect) {
                                rotate(angle) {
                                    drawCircle(brush, radius = size.maxDimension, center = center, style = Stroke(stroke.width * 2f))
                                }
                            }
                            drawPath(path, Color.White.copy(alpha = 0.06f), style = stroke)
                        }
                    }
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) Unit
                    onClick()
                },
            )
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (profile == ConnectionProfile.Fast) {
            // The lightning strikes while the card is chosen.
            LightningBadge(size = 30.dp, tint = content, alive = selected)
        } else {
            Icon(profile.icon(), null, tint = content, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(profile.label()),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) c.text else c.muted,
            maxLines = 1,
        )
        Text(
            stringResource(profile.tag()),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = if (profile == ConnectionProfile.Normal) c.ok else c.muted.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ConnectionProfile.icon(): ImageVector = when (this) {
    ConnectionProfile.Normal -> ZeroIcons.Shield
    ConnectionProfile.Fast -> ZeroIcons.Bolt
    ConnectionProfile.Gaming -> ZeroIcons.Gamepad
    ConnectionProfile.Legacy -> ZeroIcons.Clock
}

private fun ConnectionProfile.label(): Int = when (this) {
    ConnectionProfile.Normal -> R.string.profile_normal
    ConnectionProfile.Fast -> R.string.profile_fast
    ConnectionProfile.Gaming -> R.string.profile_gaming
    ConnectionProfile.Legacy -> R.string.profile_legacy
}

/** The line under the name: Normal says it is the one to pick. */
private fun ConnectionProfile.tag(): Int = when (this) {
    ConnectionProfile.Normal -> R.string.profile_tag_normal
    ConnectionProfile.Fast -> R.string.profile_tag_fast
    ConnectionProfile.Gaming -> R.string.profile_tag_gaming
    ConnectionProfile.Legacy -> R.string.profile_tag_legacy
}

private fun ConnectionProfile.hint(): Int = when (this) {
    ConnectionProfile.Normal -> R.string.profile_normal_hint
    ConnectionProfile.Fast -> R.string.profile_fast_hint
    ConnectionProfile.Gaming -> R.string.profile_gaming_hint
    ConnectionProfile.Legacy -> R.string.profile_legacy_hint
}
