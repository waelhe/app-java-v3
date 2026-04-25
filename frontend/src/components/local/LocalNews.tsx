'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Newspaper, Clock, AlertTriangle, CheckCircle, Info, ChevronLeft, ChevronRight } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';

interface NewsItem {
  id: string;
  title: string;
  titleEn: string;
  summary: string;
  summaryEn: string;
  time: string;
  type: 'alert' | 'success' | 'info';
  image: string;
}

const qudsayaCenterNews: NewsItem[] = [
  { id: '1', title: 'انقطاع الكهرباء عن حي المركز', titleEn: 'Power outage in Center district', summary: 'لأعمال صيانة طارئة حتى الساعة 6 مساءً', summaryEn: 'Emergency maintenance until 6 PM', time: 'منذ 30 دقيقة', type: 'alert', image: 'https://images.unsplash.com/photo-1473341304170-971dccb5ac1e?auto=format&fit=crop&w=300&q=80' },
  { id: '2', title: 'افتتاح مركز خدمات جديد في قدسيا', titleEn: 'New service center opened in Qudsaya', summary: 'مركز خدمات إلكترونية في منطقة الساحة', summaryEn: 'Electronic services center in Square area', time: 'منذ ساعة', type: 'success', image: 'https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=300&q=80' },
  { id: '3', title: 'تحديث: عودة الماء لجميع الأحياء', titleEn: 'Update: Water restored', summary: 'بعد انتهاء أعمال الصيانة المقررة', summaryEn: 'After scheduled maintenance completed', time: 'منذ ساعتين', type: 'info', image: 'https://images.unsplash.com/photo-1585704032915-c3400ca199e7?auto=format&fit=crop&w=300&q=80' },
  { id: '4', title: 'إعلان هام: تغيير مواعيد العمل الرسمية', titleEn: 'Important: Official working hours changed', summary: 'الدوام الرسمي الجديد من 8 صباحاً حتى 3 عصراً', summaryEn: 'New working hours from 8 AM to 3 PM', time: 'منذ 3 ساعات', type: 'info', image: 'https://images.unsplash.com/photo-1497366754035-f200968a6e72?auto=format&fit=crop&w=300&q=80' },
  { id: '5', title: 'تنبيه: أجواء باردة متوقعة', titleEn: 'Alert: Cold weather expected', summary: 'انخفاض درجات الحرارة بمقدار 10 درجات', summaryEn: 'Temperature drop by 10 degrees', time: 'منذ 4 ساعات', type: 'alert', image: 'https://images.unsplash.com/photo-1517685352747-0e1f2029e5da?auto=format&fit=crop&w=300&q=80' }
];

const qudsayaDahiaNews: NewsItem[] = [
  { id: '1', title: 'إعلان عن إغلاق طريق رئيسي للصيانة', titleEn: 'Main road closure for maintenance', summary: 'طريق الضاحية الرئيسي مغلق حتى إشعار آخر', summaryEn: 'Main Dahia road closed until further notice', time: 'منذ 15 دقيقة', type: 'alert', image: 'https://images.unsplash.com/photo-1473341304170-971dccb5ac1e?auto=format&fit=crop&w=300&q=80' },
  { id: '2', title: 'فعالية ثقافية في مركز الضاحية', titleEn: 'Cultural event at Dahia Center', summary: 'أمسية شعرية وموسيقية يوم الجمعة', summaryEn: 'Poetry and music evening on Friday', time: 'منذ ساعتين', type: 'success', image: 'https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=300&q=80' },
  { id: '3', title: 'تحديث: افتتاح مدرسة جديدة', titleEn: 'Update: New school opened', summary: 'مدرسة نموذجية في الحي الغربي', summaryEn: 'Model school in West district', time: 'منذ 3 ساعات', type: 'info', image: 'https://images.unsplash.com/photo-1580582932707-520aed937b7b?auto=format&fit=crop&w=300&q=80' },
  { id: '4', title: 'نجاح حملة تنظيف الأحياء', titleEn: 'Neighborhood cleanup campaign success', summary: 'مشاركة أكثر من 200 متطوع', summaryEn: 'Over 200 volunteers participated', time: 'منذ 5 ساعات', type: 'success', image: 'https://images.unsplash.com/photo-1532996122724-e3c354a0b15b?auto=format&fit=crop&w=300&q=80' },
  { id: '5', title: 'تنبيه: صيانة شبكة الإنترنت', titleEn: 'Alert: Internet maintenance', summary: 'انقطاع محتمل لمدة ساعتين', summaryEn: 'Possible outage for 2 hours', time: 'منذ 6 ساعات', type: 'alert', image: 'https://images.unsplash.com/photo-1544197150-b99a580bb7a8?auto=format&fit=crop&w=300&q=80' }
];

const dataByRegion: Record<Region, NewsItem[]> = {
  'qudsaya-center': qudsayaCenterNews,
  'qudsaya-dahia': qudsayaDahiaNews
};

const typeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Newspaper },
  { id: 'alert', name: 'تنبيهات', nameEn: 'Alerts', icon: AlertTriangle },
  { id: 'success', name: 'أخبار جيدة', nameEn: 'Good News', icon: CheckCircle },
  { id: 'info', name: 'تحديثات', nameEn: 'Updates', icon: Info },
];

const typeStyles = {
  alert: { bg: 'bg-red-500', text: 'text-white', icon: AlertTriangle },
  success: { bg: 'bg-emerald-500', text: 'text-white', icon: CheckCircle },
  info: { bg: 'bg-blue-500', text: 'text-white', icon: Info }
};

export default function LocalNews() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const newsItems = dataByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredNews = newsItems.filter(n => activeType === 'all' || n.type === activeType);

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredNews]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '📰 أخبار محلية' : '📰 Local News'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {typeFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeType === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveType(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredNews.map((item) => {
              const TypeIcon = typeStyles[item.type].icon;
              const styles = typeStyles[item.type];
              
              return (
                <div key={item.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={item.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <div className="absolute inset-0 bg-gradient-to-t from-black/50 via-transparent to-transparent" />
                    <span className={`absolute top-2 left-2 px-2 py-0.5 ${styles.bg} ${styles.text} text-[10px] font-semibold rounded-full flex items-center gap-1`}>
                      <TypeIcon className="w-3 h-3" />
                      {item.type === 'alert' ? (isArabic ? 'تنبيه' : 'Alert') :
                       item.type === 'success' ? (isArabic ? 'جديد' : 'Good') : (isArabic ? 'تحديث' : 'Update')}
                    </span>
                  </div>
                  <div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-2">
                      {isArabic ? item.title : item.titleEn}
                    </h3>
                    <p className="text-xs text-gray-600 mb-2 line-clamp-2">
                      {isArabic ? item.summary : item.summaryEn}
                    </p>
                    <div className="flex items-center gap-1 text-xs text-gray-400">
                      <Clock className="w-3 h-3" />
                      <span>{item.time}</span>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}
