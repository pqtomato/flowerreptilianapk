/// 地区识别：citycode 映射 + 标题推断，并归一成可筛选的规范名。
class RegionNames {
  RegionNames._();

  static const Map<String, String> _cityCodes = {
    '340800': '安庆市',
    '340801': '市辖区',
    '340802': '迎江区',
    '340803': '大观区',
    '340811': '宜秀区',
    '340822': '怀宁县',
    '340824': '潜山市',
    '340825': '太湖县',
    '340826': '宿松县',
    '340827': '望江县',
    '340828': '岳西县',
    '340881': '桐城市',
  };

  /// 顺序很重要：必须「桐城市」先于「桐城」；刻意不收录经开区 / 高新区。
  static const List<String> _titleRegions = [
    '迎江区', '大观区', '宜秀区',
    '怀宁县', '桐城市', '潜山市', '太湖县', '宿松县', '望江县', '岳西县',
    '桐城', '怀宁', '潜山', '太湖', '宿松', '望江', '岳西', '迎江', '大观', '宜秀',
  ];

  static const Map<String, String> _shortToFull = {
    '桐城': '桐城市',
    '怀宁': '怀宁县',
    '潜山': '潜山市',
    '太湖': '太湖县',
    '宿松': '宿松县',
    '望江': '望江县',
    '岳西': '岳西县',
    '迎江': '迎江区',
    '大观': '大观区',
    '宜秀': '宜秀区',
  };

  static String byCityCode(String? cityCode) {
    if (cityCode == null || cityCode.trim().isEmpty) return '';
    return _cityCodes[cityCode.trim()] ?? '';
  }

  static String guessFromTitle(String? title) {
    if (title == null || title.trim().isEmpty) return '';
    for (final region in _titleRegions) {
      if (title.contains(region)) {
        return _shortToFull[region] ?? region;
      }
    }
    return '';
  }

  static List<String> knownRegions() => _cityCodes.values.toSet().toList();
}
