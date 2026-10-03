package com.simorgh.mac.ui.home

import com.simorgh.mac.str.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.simorgh.mac.str.LocalContext
import com.simorgh.mac.str.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.simorgh.mac.R
import com.simorgh.mac.model.ConnState
import com.simorgh.mac.model.ConnectTarget
import com.simorgh.mac.model.FailReason
import com.simorgh.mac.model.Server
import com.simorgh.mac.ui.LocalController
import com.simorgh.mac.ui.components.FlagBadge
import com.simorgh.mac.ui.components.ServerBadge
import com.simorgh.mac.ui.components.IconBadge
import com.simorgh.mac.ui.components.RowShape
import com.simorgh.mac.ui.components.SectionTitle
import com.simorgh.mac.ui.components.ZeroSheet
import com.simorgh.mac.ui.icons.ZeroIcons
import com.simorgh.mac.ui.model.CountryGroup
import com.simorgh.mac.ui.model.countryLabel
import com.simorgh.mac.ui.model.groupByCountry
import com.simorgh.mac.ui.model.serverTitle
import com.simorgh.mac.ui.theme.ZeroTheme
import com.simorgh.mac.ui.util.Num
import com.simorgh.mac.ui.util.currentLocale
import com.simorgh.mac.ui.util.formatDelay
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeRoute() {
    val controller = LocalController.current
    val conn by controller.engine.state.collectAsState()
    val stats by controller.engine.stats.collectAsState()
    val race by controller.engine.race.collectAsState()
    val settings by controller.settings.settings.collectAsState()
    val servers by controller.servers.servers.collectAsState()
    val subscriptions by controller.servers.subscriptions.collectAsState()
    val target = remember(settings.lastTarget) { ConnectTarget.decode(settings.lastTarget) }
    val groups by produceState(emptyList<CountryGroup>(), servers) {
        value = withContext(Dispatchers.Default) { groupByCountry(servers) }
    }
    val targetServer = remember(target, servers) { (target as? ConnectTarget.Specific)?.let { t -> servers.firstOrNull { it.key == t.key } } }
    val countryDelay = remember(target, groups) { (target as? ConnectTarget.Country)?.let { t -> groups.firstOrNull { it.code == t.code }?.bestDelay } ?: -1 }
    val subscriptionRows = remember(subscriptions, servers) {
        subscriptions.map { sub ->
            val own = servers.filter { it.source == Server.SOURCE_SUB_PREFIX + sub.id }
            SubscriptionRow(sub.id, sub.name.ifBlank { null }, own.size, own.filter { it.delayMs >= 0 }.minOfOrNull { it.delayMs } ?: -1)
        }
    }
    val targetSubscription = remember(target, subscriptionRows) { (target as? ConnectTarget.Subscription)?.let { t -> subscriptionRows.firstOrNull { it.id == t.id } } }
    var picker by rememberSaveable { mutableStateOf(false) }

    HomeScreen(
        state = HomeState(
            conn = conn,
            stats = stats,
            target = target,
            targetServer = targetServer,
            targetCountryDelay = countryDelay,
            targetSubscription = targetSubscription?.name,
            targetSubscriptionDelay = targetSubscription?.bestDelay ?: -1,
            profile = settings.profile,
            race = race,
        ),
        onOrbClick = controller::toggle,
        onRetry = {
            val failed = conn as? ConnState.Failed
            if (failed?.reason == FailReason.ServerUnavailable) controller.selectTarget(ConnectTarget.Fastest) else controller.connect()
        },
        onPickServer = { picker = true },
        onProfile = { p -> controller.update { it.copy(profile = p) } },
    )

    ServerPickerSheet(
        visible = picker,
        target = target,
        groups = groups,
        subscriptions = subscriptionRows,
        mine = remember(servers) { servers.filter { it.isUser } },
        favorites = remember(servers) { servers.filter { it.favorite && !it.isUser } },
        onSelect = {
            picker = false
            controller.selectTarget(it)
        },
        onDismiss = { picker = false },
    )
}

/** A subscription as the picker lists it. */
data class SubscriptionRow(val id: String, val name: String?, val count: Int, val bestDelay: Int)

/** Fastest / your subscriptions / your configs / favourites / countries. Choosing one saves it as the target and connects. */
@Composable
fun ServerPickerSheet(
    visible: Boolean,
    target: ConnectTarget,
    groups: List<CountryGroup>,
    subscriptions: List<SubscriptionRow>,
    mine: List<Server>,
    favorites: List<Server>,
    onSelect: (ConnectTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.picker_title)
    ZeroSheet(visible = visible, onDismiss = onDismiss, title = title) {
        val c = ZeroTheme.colors
        val context = LocalContext.current
        val locale = currentLocale()
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = c.text,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            item(key = "fastest", contentType = "row") {
                PickerRow(
                    selected = target == ConnectTarget.Fastest,
                    leading = { com.simorgh.mac.ui.components.LightningBadge() },
                    title = stringResource(R.string.server_fastest),
                    subtitle = stringResource(R.string.server_fastest_hint),
                    trailing = null,
                    onClick = { onSelect(ConnectTarget.Fastest) },
                )
            }
            if (subscriptions.isNotEmpty()) {
                item(key = "subs_title", contentType = "title") { SectionTitle(stringResource(R.string.picker_subscriptions), Modifier.padding(start = 8.dp, top = 8.dp)) }
                items(subscriptions, key = { "sub_" + it.id }, contentType = { "row" }) { sub ->
                    PickerRow(
                        selected = target == ConnectTarget.Subscription(sub.id),
                        leading = { IconBadge(ZeroIcons.Link) },
                        title = sub.name ?: stringResource(R.string.source_subscription),
                        subtitle = pluralStringResource(R.plurals.servers_count, sub.count, Num.int(sub.count, locale)),
                        trailing = if (sub.bestDelay >= 0) formatDelay(context, sub.bestDelay, locale) else null,
                        trailingColor = c.delayColor(sub.bestDelay),
                        onClick = { onSelect(ConnectTarget.Subscription(sub.id)) },
                    )
                }
            }
            if (mine.isNotEmpty()) {
                item(key = "mine_title", contentType = "title") { SectionTitle(stringResource(R.string.picker_mine), Modifier.padding(start = 8.dp, top = 8.dp)) }
                items(mine, key = { "mine_" + it.key }, contentType = { "row" }) { s ->
                    PickerRow(
                        selected = target == ConnectTarget.Specific(s.key),
                        leading = { ServerBadge(s) },
                        title = serverTitle(context, s, locale),
                        subtitle = if (s.country.isNotEmpty()) countryLabel(context, s.country, locale) else s.protocol.uppercase(Locale.ROOT),
                        trailing = if (s.delayMs >= 0) formatDelay(context, s.delayMs, locale) else null,
                        trailingColor = c.delayColor(s.delayMs),
                        onClick = { onSelect(ConnectTarget.Specific(s.key)) },
                    )
                }
            }
            if (favorites.isNotEmpty()) {
                item(key = "fav_title", contentType = "title") { SectionTitle(stringResource(R.string.picker_favorites), Modifier.padding(start = 8.dp, top = 8.dp)) }
                items(favorites, key = { "fav_" + it.key }, contentType = { "row" }) { s ->
                    PickerRow(
                        selected = target == ConnectTarget.Specific(s.key),
                        leading = { ServerBadge(s) },
                        title = serverTitle(context, s, locale),
                        subtitle = countryLabel(context, s.country, locale),
                        trailing = if (s.delayMs >= 0) formatDelay(context, s.delayMs, locale) else null,
                        trailingColor = c.delayColor(s.delayMs),
                        onClick = { onSelect(ConnectTarget.Specific(s.key)) },
                    )
                }
            }
            val known = groups.filter { it.code.isNotEmpty() }
            if (known.isNotEmpty()) {
                item(key = "cty_title", contentType = "title") { SectionTitle(stringResource(R.string.picker_countries), Modifier.padding(start = 8.dp, top = 8.dp)) }
                items(known, key = { "cty_" + it.code }, contentType = { "row" }) { g ->
                    PickerRow(
                        selected = target == ConnectTarget.Country(g.code),
                        leading = { FlagBadge(g.code) },
                        title = countryLabel(context, g.code, locale),
                        subtitle = pluralStringResource(R.plurals.servers_count, g.count, Num.int(g.count, locale)),
                        trailing = if (g.bestDelay >= 0) formatDelay(context, g.bestDelay, locale) else null,
                        trailingColor = c.delayColor(g.bestDelay),
                        onClick = { onSelect(ConnectTarget.Country(g.code)) },
                    )
                }
            } else {
                item(key = "empty", contentType = "empty") {
                    Text(
                        stringResource(R.string.picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    selected: Boolean,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: String?,
    onClick: () -> Unit,
    trailingColor: androidx.compose.ui.graphics.Color = ZeroTheme.colors.muted,
) {
    val c = ZeroTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RowShape)
            .background(if (selected) c.accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = trailingColor)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(ZeroIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
    }
}
