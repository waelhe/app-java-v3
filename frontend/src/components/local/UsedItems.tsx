'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Package, Heart, Clock, MapPin, Percent, ChevronLeft, ChevronRight, Smartphone, Sofa, Car, Home } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface UsedItem {
  id: string;
  title: string;
  titleEn: string;
  price: string;
  originalPrice: string;
  condition: string;
  conditionEn: string;
  category: 'electronics' | 'furniture' | 'appliances' | 'sports' | 'clothes' | 'other';
  categoryAr: string;
  categoryEn: string;
  images: string[];
  location: string;
  time: string;
  featured?: boolean;
  urgent?: boolean;
}

const qudsayaCenterItems: UsedItem[] = [
  {
    id: '1',
    title: 'ثلاجة سامسونج نوفروست',
    titleEn: 'Samsung NoFrost Fridge',
    price: '380',
    originalPrice: '650',
    condition: 'جيدة جداً',
    conditionEn: 'Very Good',
    category: 'appliances',
    categoryAr: 'أجهزة',
    categoryEn: 'Appliances',
    images: ['https://images.unsplash.com/photo-1571175443880-49e1d25b2bc5?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا - الحي الغربي',
    time: 'منذ يوم',
    featured: true
  },
  {
    id: '2',
    title: 'طقم كنب مودرن 7 قطع',
    titleEn: 'Modern Sofa Set 7 Pieces',
    price: '850',
    originalPrice: '1,600',
    condition: 'ممتاز',
    conditionEn: 'Excellent',
    category: 'furniture',
    categoryAr: 'أثاث',
    categoryEn: 'Furniture',
    images: ['https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا - المركز',
    time: 'منذ 3 أيام',
    featured: true
  },
  {
    id: '3',
    title: 'لابتوب HP برو بوك i7',
    titleEn: 'HP ProBook i7 Laptop',
    price: '420',
    originalPrice: '850',
    condition: 'جيد',
    conditionEn: 'Good',
    category: 'electronics',
    categoryAr: 'إلكترونيات',
    categoryEn: 'Electronics',
    images: ['https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا',
    time: 'منذ ساعتين',
    urgent: true
  },
  {
    id: '4',
    title: 'دراجة هوائية جبلية',
    titleEn: 'Mountain Bike',
    price: '160',
    originalPrice: '320',
    condition: 'جيدة',
    conditionEn: 'Good',
    category: 'sports',
    categoryAr: 'رياضة',
    categoryEn: 'Sports',
    images: ['https://images.unsplash.com/photo-1532298229144-0ec0c57515c7?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا',
    time: 'منذ أسبوع'
  },
  {
    id: '5',
    title: 'غسالة LG أوتوماتيك',
    titleEn: 'LG Automatic Washing Machine',
    price: '280',
    originalPrice: '500',
    condition: 'جيدة جداً',
    conditionEn: 'Very Good',
    category: 'appliances',
    categoryAr: 'أجهزة',
    categoryEn: 'Appliances',
    images: ['https://images.unsplash.com/photo-1626806787461-102c1bfaaea1?auto=format&fit=crop&w=400&q=80'],
    location: 'قدسيا - الشارع الرئيسي',
    time: 'منذ 4 أيام'
  }
];

const qudsayaDahiaItems: UsedItem[] = [
  {
    id: '1',
    title: 'ثلاجة ال جي إنفرتر',
    titleEn: 'LG Inverter Fridge',
    price: '350',
    originalPrice: '600',
    condition: 'جيدة جداً',
    conditionEn: 'Very Good',
    category: 'appliances',
    categoryAr: 'أجهزة',
    categoryEn: 'Appliances',
    images: ['https://images.unsplash.com/photo-1571175443880-49e1d25b2bc5?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية',
    time: 'منذ يوم',
    featured: true
  },
  {
    id: '2',
    title: 'طقم كنب كلاسيكي',
    titleEn: 'Classic Sofa Set',
    price: '800',
    originalPrice: '1,500',
    condition: 'ممتاز',
    conditionEn: 'Excellent',
    category: 'furniture',
    categoryAr: 'أثاث',
    categoryEn: 'Furniture',
    images: ['https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية - الحي الرئيسي',
    time: 'منذ 3 أيام'
  },
  {
    id: '3',
    title: 'لابتوب ديل XPS 15',
    titleEn: 'Dell XPS 15 Laptop',
    price: '550',
    originalPrice: '1,000',
    condition: 'جيد',
    conditionEn: 'Good',
    category: 'electronics',
    categoryAr: 'إلكترونيات',
    categoryEn: 'Electronics',
    images: ['https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية',
    time: 'منذ ساعتين',
    urgent: true
  },
  {
    id: '4',
    title: 'بلايستيشن 5 مع ألعاب',
    titleEn: 'PlayStation 5 with Games',
    price: '480',
    originalPrice: '700',
    condition: 'ممتاز',
    conditionEn: 'Excellent',
    category: 'electronics',
    categoryAr: 'إلكترونيات',
    categoryEn: 'Electronics',
    images: ['https://images.unsplash.com/photo-1606144042614-b2417e99c4e3?auto=format&fit=crop&w=400&q=80'],
    location: 'الضاحية',
    time: 'منذ 5 ساعات',
    featured: true
  }
];

const dataByRegion: Record<Region, UsedItem[]> = {
  'qudsaya-center': qudsayaCenterItems,
  'qudsaya-dahia': qudsayaDahiaItems
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Package },
  { id: 'electronics', name: 'إلكترونيات', nameEn: 'Electronics', icon: Smartphone },
  { id: 'furniture', name: 'أثاث', nameEn: 'Furniture', icon: Sofa },
  { id: 'appliances', name: 'أجهزة', nameEn: 'Appliances', icon: Home },
  { id: 'sports', name: 'رياضة', nameEn: 'Sports', icon: Car },
];

export default function UsedItems() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const items = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredItems = items.filter(item => {
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
  }, [filteredItems]);

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

  const getDiscount = (price: string, original: string) => {
    const p = parseInt(price.replace(/,/g, ''));
    const o = parseInt(original.replace(/,/g, ''));
    return Math.round(((o - p) / o) * 100);
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
            {filteredItems.slice(0, 8).map((item) => {
              const isFavorite = favorites.includes(item.id);
              const discount = getDiscount(item.price, item.originalPrice);
              
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

                    {/* Discount Badge */}
                    <div className="absolute top-2 left-2 px-2 py-0.5 bg-rose-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm flex items-center gap-0.5">
                      <Percent className="w-2.5 h-2.5" />
                      {discount}%
                    </div>

                    {/* Condition Badge */}
                    <div className="absolute bottom-2 left-2">
                      <span className={`px-2 py-0.5 text-[9px] font-semibold rounded-full backdrop-blur-sm ${
                        item.condition === 'ممتاز' ? 'bg-emerald-500/90 text-white' : 
                        item.condition === 'جيدة جداً' ? 'bg-blue-500/90 text-white' : 
                        'bg-gray-500/90 text-white'
                      }`}>
                        {isArabic ? item.condition : item.conditionEn}
                      </span>
                    </div>

                    {/* Featured/Urgent Badge */}
                    {(item.featured || item.urgent) && (
                      <div className="absolute bottom-2 right-2">
                        <span className={`px-2 py-0.5 text-[9px] font-semibold rounded-full backdrop-blur-sm ${
                          item.featured ? 'bg-amber-500/90 text-white' : 'bg-red-500/90 text-white animate-pulse'
                        }`}>
                          {item.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'عاجل' : 'Urgent')}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Item Info - Airbnb Style */}
                  <div>
                    {/* Title */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-2">
                      {isArabic ? item.title : item.titleEn}
                    </h3>

                    {/* Price */}
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-sm font-bold text-gray-900">${item.price}</span>
                      <span className="text-xs text-gray-400 line-through">${item.originalPrice}</span>
                    </div>

                    {/* Location & Time */}
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {item.location}</span>
                      <span className="flex items-center gap-0.5 flex-shrink-0">
                        <Clock className="w-3 h-3" />
                        {item.time}
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
                  {filteredItems.slice(0, 4).map((it, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={it.images[0]} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredItems.length} {isArabic ? 'سلعة' : 'items'}</span>
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
              {isArabic ? 'جميع السلع المستعملة' : 'All Used Items'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredItems.length} {isArabic ? 'سلعة متاحة' : 'items available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredItems.map((item) => {
                const isFavorite = favorites.includes(item.id);
                const discount = getDiscount(item.price, item.originalPrice);
                return (
                  <div key={item.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={item.images[0]} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(item.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <div className="absolute top-2 left-2 px-2 py-0.5 bg-rose-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm flex items-center gap-0.5">
                        <Percent className="w-2.5 h-2.5" />{discount}%
                      </div>
                      <span className={`absolute bottom-2 left-2 px-2 py-0.5 text-[9px] font-semibold rounded-full backdrop-blur-sm ${item.condition === 'ممتاز' ? 'bg-emerald-500/90 text-white' : item.condition === 'جيدة جداً' ? 'bg-blue-500/90 text-white' : 'bg-gray-500/90 text-white'}`}>
                        {isArabic ? item.condition : item.conditionEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? item.title : item.titleEn}</h3>
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-sm font-bold text-gray-900">${item.price}</span>
                      <span className="text-xs text-gray-400 line-through">${item.originalPrice}</span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {item.location}</span>
                      <span className="flex items-center gap-0.5 flex-shrink-0"><Clock className="w-3 h-3" />{item.time}</span>
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
