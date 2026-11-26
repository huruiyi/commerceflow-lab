-- The per-merchant primary key rejects conflicting aliases. InnoDB rolls back
-- this statement on conflict, preserving both original rates for review.
UPDATE merchant_shipping_rates
SET state_province = CASE
  WHEN state_province IN ('河北', '山西', '辽宁', '吉林', '黑龙江', '江苏', '浙江', '安徽',
    '福建', '江西', '山东', '河南', '湖北', '湖南', '广东', '海南', '四川', '贵州', '云南',
    '陕西', '甘肃', '青海', '台湾') THEN CONCAT(state_province, '省')
  WHEN state_province IN ('北京', '天津', '上海', '重庆') THEN CONCAT(state_province, '市')
  WHEN state_province IN ('香港', '澳门') THEN CONCAT(state_province, '特别行政区')
  WHEN state_province IN ('内蒙古', '西藏') THEN CONCAT(state_province, '自治区')
  WHEN state_province = '广西' THEN '广西壮族自治区'
  WHEN state_province = '宁夏' THEN '宁夏回族自治区'
  WHEN state_province = '新疆' THEN '新疆维吾尔自治区'
  ELSE state_province
END
WHERE country_code = 'CN';
