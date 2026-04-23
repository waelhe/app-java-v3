'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Megaphone, Heart, Clock, MapPin, ChevronLeft, ChevronRight, Car, Sofa, Smartphone, Home, Briefcase, Sparkles } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Classified {
  id: string;
  title: string;
  titleEn: string;
  price: string;
  category: 'cars' | 'furniture' | 'electronics' | 'appliances' | 'services' | 'other';
  categoryAr: string;
  categoryEn: string;
  images: string[];
  location: string;
  time: string;
  timeEn: string;
  featured?: boolean;
  urgent?: boolean;
  verified?: boolean;
}

const qudsayaCenterClassifieds: Classified[] = [
  {
    id: '1',
    title: 'سيارة تويوتا كامري 2020',
    titleEn: 'Toyota Camry 2020',
    price: '35,000',
    category: 'cars',
    categoryAr: 'سيارات',
    categoryEn: 'Cars',
    images: ['https://images.unsplash.com/photo-1621007947382-bb3c3994e3fb?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا - الحي الغربي',
    time: 'منذ ساعتين',
    timeEn: '2 hours ago',
    featured: true,
    verified: true
  },
  {
    id: '2',
    title: 'أريكة جلدية فاخرة إيطالية',
    titleEn: 'Luxury Italian Leather Sofa',
    price: '1,200',
    category: 'furniture',
    categoryAr: 'أثاث',
    categoryEn: 'Furniture',
    images: ['https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا - المركز',
    time: 'منذ يوم',
    timeEn: '1 day ago',
    verified: true
  },
  {
    id: '3',
    title: 'آيفون 15 برو ماكس 512GB',
    titleEn: 'iPhone 15 Pro Max 512GB',
    price: '1,800',
    category: 'electronics',
    categoryAr: 'إلكترونيات',
    categoryEn: 'Electronics',
    images: ['https://images.unsplash.com/photo-1695048133142-1a20484d2569?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا',
    time: 'منذ 3 ساعات',
    timeEn: '3 hours ago',
    urgent: true
  },
  {
    id: '4',
    title: 'غسالة سامسونج جديدة بالضمان',
    titleEn: 'Samsung Washing Machine New with Warranty',
    price: '650',
    category: 'appliances',
    categoryAr: 'أجهزة',
    categoryEn: 'Appliances',
    images: ['https://images.unsplash.com/photo-1626806787461-102c1bfaaea1?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا - الشارع الرئيسي',
    time: 'منذ 5 ساعات',
    timeEn: '5 hours ago'
  },
  {
    id: '5',
    title: 'خدمات نقل أثاث وعفش',
    titleEn: 'Furniture Moving Services',
    price: 'حسب الطلب',
    category: 'services',
    categoryAr: 'خدمات',
    categoryEn: 'Services',
    images: ['https://images.unsplash.com/photo-1600518464441-9154a4dea21b?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا',
    time: 'منذ أسبوع',
    timeEn: '1 week ago',
    featured: true
  }
];

const qudsayaDahiaClassifieds: Classified[] = [
  {
    id: '1',
    title: 'سيارة هيونداي سوناتا 2019',
    titleEn: 'Hyundai Sonata 2019',
    price: '28,000',
    category: 'cars',
    categoryAr: 'سيارات',
    categoryEn: 'Cars',
    images: ['https://images.unsplash.com/photo-1621007947382-bb3c3994e3fb?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية',
    time: 'منذ ساعة',
    timeEn: '1 hour ago',
    featured: true,
    verified: true
  },
  {
    id: '2',
    title: 'طقم كنب مودرن 9 قطع',
    titleEn: 'Modern Sofa Set 9 Pieces',
    price: '950',
    category: 'furniture',
    categoryAr: 'أثاث',
    categoryEn: 'Furniture',
    images: ['https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية - الحي الرئيسي',
    time: 'منذ يومين',
    timeEn: '2 days ago'
  },
  {
    id: '3',
    title: 'لابتوب ديل XPS 15 الجيل 12',
    titleEn: 'Dell XPS 15 Gen 12 Laptop',
    price: '1,200',
    category: 'electronics',
    categoryAr: 'إلكترونيات',
    categoryEn: 'Electronics',
    images: ['https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية',
    time: 'منذ 4 ساعات',
    timeEn: '4 hours ago',
    urgent: true
  },
  {
    id: '4',
    title: 'خدمات تنظيف منازل',
    titleEn: 'Home Cleaning Services',
    price: '50/زيارة',
    category: 'services',
    categoryAr: 'خدمات',
    categoryEn: 'Services',
    images: ['https://images.unsplash.com/photo-1581578731548-c64695cc6952?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية',
    time: 'منذ 3 أيام',
    timeEn: '3 days ago',
    verified: true
  }
];

const dataByRegion: Record<Region, Classified[]> = {
  'qudsaya-center': qudsayaCenterClassifieds,
  'qudsaya-dahia': qudsayaDahiaClassifieds
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Megaphone },
  { id: 'cars', name: 'سيارات', nameEn: 'Cars', icon: Car },
  { id: 'furniture', name: 'أثاث', nameEn: 'Furniture', icon: Sofa },
  { id: 'electronics', name: 'إلكترونيات', nameEn: 'Electronics', icon: Smartphone },
  { id: 'appliances', name: 'أجهزة', nameEn: 'Appliances', icon: Home },
  { id: 'services', name: 'خدمات', nameEn: 'Services', icon: Briefcase },
];

export default function Classifieds() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const classifieds = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredClassifieds = classifieds.filter(item => {
    return activeCategory === 'all' || item.category === activeCategory;
  });

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
  }, [filteredClassifieds]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -280 : 280,
        behavior: 'smooth'
      });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => 
      prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]
    );
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive 
                    ? 'border-gray-900 text-gray-900' 
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
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
          {/* Navigation Buttons */}
          {canScrollLeft && (
            <button
              onClick={() => scroll('left')}
              className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button
              onClick={() => scroll('right')}
              className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          {/* Scrollable Cards */}
          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {filteredClassifieds.slice(0, 8).map((item) => {
              const isFavorite = favorites.includes(item.id);
              
              return (
                <div
                  key={item.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img 
                      src={item.images[0]} 
                      alt="" 
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(item.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart 
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} 
                      />
                    </button>

                    {/* Category Badge */}
                    <div className="absolute top-2 left-2">
                      <span className="px-2 py-0.5 bg-violet-600/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? item.categoryAr : item.categoryEn}
                      </span>
                    </div>

                    {/* Badges */}
                    <div className="absolute bottom-2 right-2 flex gap-1">
                      {item.verified && (
                        <span className="px-2 py-0.5 bg-blue-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm flex items-center gap-0.5">
                          <Sparkles className="w-2.5 h-2.5" />
                          {isArabic ? 'موثق' : 'Verified'}
                        </span>
                      )}
                      {item.featured && (
                        <span className="px-2 py-0.5 bg-amber-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm">
                          {isArabic ? 'مميز' : 'Featured'}
                        </span>
                      )}
                      {item.urgent && (
                        <span className="px-2 py-0.5 bg-red-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm animate-pulse">
                          {isArabic ? 'عاجل' : 'Urgent'}
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Item Info - Airbnb Style */}
                  <div>
                    {/* Title */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-2">
                      {isArabic ? item.title : item.titleEn}
                    </h3>

                    {/* Price */}
                    <div className="flex items-center gap-1 mb-1">
                      <span className="text-sm font-bold text-violet-600">${item.price}</span>
                    </div>

                    {/* Location & Time */}
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {item.location}</span>
                      <span className="flex items-center gap-0.5 flex-shrink-0">
                        <Clock className="w-3 h-3" />
                        {isArabic ? item.time : item.timeEn}
                      </span>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* View All Card */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {filteredClassifieds.slice(0, 4).map((it, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={it.images[0]} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredClassifieds.length} {isArabic ? 'إعلان' : 'ads'}</span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع الإعلانات' : 'All Classifieds'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredClassifieds.length} {isArabic ? 'إعلان متاح' : 'ads available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredClassifieds.map((item) => {
                const isFavorite = favorites.includes(item.id);
                return (
                  <div key={item.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={item.images[0]} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(item.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 bg-violet-600/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? item.categoryAr : item.categoryEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? item.title : item.titleEn}</h3>
                    <div className="flex items-center gap-1 mb-1">
                      <span className="text-sm font-bold text-violet-600">${item.price}</span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {item.location}</span>
                      <span className="flex items-center gap-0.5 flex-shrink-0"><Clock className="w-3 h-3" />{isArabic ? item.time : item.timeEn}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </Drawer.Content>
      </Drawer.Root>
    </section>
  );
}
