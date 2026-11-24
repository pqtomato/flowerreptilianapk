import 'package:material_ui/material_ui.dart';

import '../../core/models.dart';
import 'badges.dart';
import 'format.dart';

/// 公告卡片：截止日期永远第一眼看到，其次才是预算和命中词。
class TenderCard extends StatelessWidget {
  const TenderCard({
    super.key,
    required this.record,
    required this.onTap,
    required this.onToggleTrack,
  });

  final TenderRecord record;
  final VoidCallback onTap;
  final VoidCallback onToggleTrack;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    final meta = <String>[
      siteShortName(record.siteCode),
      if (record.region.isNotEmpty) record.region,
      if (record.category.isNotEmpty) record.category,
      if (record.publishDate != null) '发布 ${formatDay(record.publishDate)}',
      if (record.deadlineDate != null) '截止 ${formatDay(record.deadlineDate)}',
    ];
    final keywords = matchedKeywordList(record.matchedKeywords);

    final titleStyle = theme.textTheme.titleSmall?.copyWith(
      fontSize: 15.5,
      height: 1.42,
      fontWeight: FontWeight.w600,
      color: scheme.onSurface,
    );
    final highlight = TextStyle(
      color: scheme.primary,
      fontWeight: FontWeight.w800,
      backgroundColor: scheme.primaryContainer.withValues(alpha: 0.45),
    );

    return Card(
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 11, 4, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    RichText(
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                      text: TextSpan(
                        style: titleStyle,
                        children: highlightTitle(
                          record.title,
                          record.matchedKeywords,
                          highlight: highlight,
                        ),
                      ),
                    ),
                    const SizedBox(height: 9),
                    Wrap(
                      spacing: 6,
                      runSpacing: 5,
                      children: tenderBadges(record),
                    ),
                    if (meta.isNotEmpty) ...[
                      const SizedBox(height: 9),
                      Text(
                        meta.join(' · '),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(
                          fontSize: 12,
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                    if (keywords.isNotEmpty) ...[
                      const SizedBox(height: 9),
                      Wrap(
                        spacing: 6,
                        runSpacing: 5,
                        children: keywords
                            .map((keyword) => _KeywordTag(keyword))
                            .toList(),
                      ),
                    ],
                  ],
                ),
              ),
              IconButton(
                onPressed: onToggleTrack,
                tooltip: record.tracked ? '取消收藏' : '收藏',
                iconSize: 22,
                padding: const EdgeInsets.all(8),
                constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
                icon: Icon(
                  record.tracked ? Icons.star_rounded : Icons.star_border_rounded,
                  color: record.tracked ? const Color(0xFFF2A600) : scheme.outline,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _KeywordTag extends StatelessWidget {
  const _KeywordTag(this.keyword);

  final String keyword;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: scheme.secondaryContainer.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        keyword,
        style: TextStyle(
          fontSize: 11,
          height: 1.3,
          color: scheme.onSecondaryContainer,
        ),
      ),
    );
  }
}
