'use client';

import React, { useRef, useState, useEffect } from 'react';
import { TrendingUp, TrendingDown, Minus, Apple, Carrot, Beef, Milk, Wheat, ChevronLeft, ChevronRight, Grid3X3 } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface MarketItem {
  id: string;
  name: string;
  nameEn: string;
  category: 'vegetables' | 'fruits' | 'meat' | 'dairy' | 'legumes';
  price: number;
  unit: string;
  unitEn: string;
  change: 'up' | 'down' | 'stable';
  changePercent?: number;
}

const marketItems: MarketItem[] = [
  { id: 'v1', name: 'طماطم', nameEn: 'Tomato', category: 'vegetables', price: 1500, unit: 'كيلو', unitEn: 'kg', change: 'up', changePercent: 5 },
  { id: 'v2', name: 'خيار', nameEn: 'Cucumber', category: 'vegetables', price: 1200, unit: 'كيلو', unitEn: 'kg', change: 'stable' },
  { id: 'v3', name: 'بطاطا', nameEn: 'Potato', category: 'vegetables', price: 1000, unit: 'كيلو', unitEn: 'kg', change: 'down', changePercent: 3 },
  { id: 'v4', name: 'بصل', nameEn: 'Onion', category: 'vegetables', price: 800, unit: 'كيلو', unitEn: 'kg', change: 'stable' },
  { id: 'v5', name: 'خس', nameEn: 'Lettuce', category: 'vegetables', price: 2000, unit: 'حبة', unitEn: 'piece', change: 'up', changePercent: 10 },
  { id: 'f1', name: 'تفاح', nameEn: 'Apple', category: 'fruits', price: 2500, unit: 'كيلو', unitEn: 'kg', change: 'stable' },
  { id: 'f2', name: 'موز', nameEn: 'Banana', category: 'fruits', price: 3000, unit: 'كيلو', unitEn: 'kg', change: 'up', changePercent: 8 },
  { id: 'f3', name: 'برتقال', nameEn: 'Orange', category: 'fruits', price: 1500, unit: 'كيلو', unitEn: 'kg', change: 'down', changePercent: 5 },
  { id: 'f4', name: 'عنب', nameEn: 'Grapes', category: 'fruits', price: 4000, unit: 'كيلو', unitEn: 'kg', change: 'up', changePercent: 12 },
  { id: 'm1', name: 'لحم غنم', nameEn: 'Lamb Meat', category: 'meat', price: 45000, unit: 'كيلو', unitEn: 'kg', change: 'up', changePercent: 3 },
  { id: 'm2', name: 'لحم بقر', nameEn: 'Beef', category: 'meat', price: 35000, unit: 'كيلو', unitEn: 'kg', change: 'stable' },
  { id: 'm3', name: 'دجاج', nameEn: 'Chicken', category: 'meat', price: 12000, unit: 'كيلو', unitEn: 'kg', change: 'down', changePercent: 2 },
  { id: 'd1', name: 'حليب', nameEn: 'Milk', category: 'dairy', price: 3000, unit: 'لتر', unitEn: 'liter', change: 'stable' },
  { id: 'd2', name: 'جبنة بيضاء', nameEn: 'White Cheese', category: 'dairy', price: 15000, unit: 'كيلو', unitEn: 'kg', change: 'up', changePercent: 5 },
  { id: 'd3', name: 'لبن', nameEn: 'Yogurt', category: 'dairy', price: 2500, unit: 'كيلو', unitEn: 'kg', change: 'stable' },
  { id: 'l1', name: 'أرز', nameEn: 'Rice', category: 'legumes', price: 5000, unit: 'كيلو', unitEn: 'kg', change: 'down', changePercent: 4 },
  { id: 'l2', name: 'عدس', nameEn: 'Lentils', category: 'legumes', price: 4000, unit: 'كيلو', unitEn: 'kg', change: 'stable' },
  { id: 'l3', name: 'فول', nameEn: 'Beans', category: 'legumes', price: 4500, unit: 'كيلو', unitEn: 'kg', change: 'up', changePercent: 2 },
];

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Apple },
  { id: 'vegetables', name: 'خضروات', nameEn: 'Vegetables', icon: Carrot },
  { id: 'fruits', name: 'فواكه', nameEn: 'Fruits', icon: Apple },
  { id: 'meat', name: 'لحوم', nameEn: 'Meat', icon: Beef },
  { id: 'dairy', name: 'ألبان', nameEn: 'Dairy', icon: Milk },
  { id: 'legumes', name: 'بقوليات', nameEn: 'Legumes', icon: Wheat },
];

export default function MarketPrices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();

  const [activeCategory, setActiveCategory] = useState('all');
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredItems = marketItems.filter(item =>
    activeCategory === 'all' || item.category === activeCategory
  );

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
        left: direction === 'left' ? -200 : 200,
        behavior: 'smooth'
      });
    }
  };

  const formatPrice = (price: number) => {
    return price.toLocaleString('ar-SY');
  };

  const getChangeIcon = (change: string) => {
    switch (change) {
      case 'up':
        return <TrendingUp className="w-3.5 h-3.5 text-red-500" />;
      case 'down':
        return <TrendingDown className="w-3.5 h-3.5 text-green-500" />;
      default:
        return <Minus className="w-3.5 h-3.5 text-gray-400" />;
    }
  };

  const getChangeColor = (change: string) => {
    switch (change) {
      case 'up':
        return 'text-red-500';
      case 'down':
        return 'text-green-500';
      default:
        return 'text-gray-400';
    }
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '📊 أسعار السوق' : '📊 Market Prices'}
        </h2>

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

          <div ref={scrollRef} className="flex gap-3 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredItems.slice(0, 8).map((item) => {
              return (
                <div key={item.id} className="flex-shrink-0 w-[140px] sm:w-[160px] bg-white rounded-xl border border-gray-200 shadow-sm hover:shadow-md transition-shadow p-3">
                  <h3 className="text-sm font-bold text-gray-900 mb-1 line-clamp-1">
                    {isArabic ? item.name : item.nameEn}
                  </h3>
                  <div className="flex items-baseline gap-1 mb-1">
                    <span className="text-lg font-black text-emerald-600">
                      {formatPrice(item.price)}
                    </span>
                    <span className="text-[10px] text-gray-500">
                      {isArabic ? item.unit : item.unitEn}
                    </span>
                  </div>
                  <div className="flex items-center gap-1">
                    {getChangeIcon(item.change)}
                    {item.changePercent && (
                      <span className={`text-xs font-bold ${getChangeColor(item.change)}`}>
                        {item.change === 'up' ? '+' : '-'}{item.changePercent}%
                      </span>
                    )}
                    {item.change === 'stable' && (
                      <span className="text-xs text-gray-400">
                        {isArabic ? 'ثابت' : 'Stable'}
                      </span>
                    )}
                  </div>
                  <div className="mt-2">
                    <span className="text-[10px] px-2 py-0.5 bg-emerald-100 text-emerald-700 rounded-full font-bold">
                      {isArabic
                        ? categoryFilters.find(f => f.id === item.category)?.name
                        : categoryFilters.find(f => f.id === item.category)?.nameEn}
                    </span>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredItems.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[140px] sm:w-[160px] bg-white rounded-xl border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors p-3 cursor-pointer flex flex-col items-center justify-center min-h-[120px]"
              >
                <Grid3X3 className="w-8 h-8 text-gray-400 mb-2" />
                <span className="text-lg font-bold text-gray-700">+{filteredItems.length - 8}</span>
                <span className="text-xs text-gray-500">{isArabic ? 'عرض الكل' : 'View All'}</span>
              </div>
            )}
          </div>
        </div>

        <p className="text-xs text-gray-400 text-center mt-2">
          {isArabic ? 'آخر تحديث: اليوم' : 'Last updated: Today'}
        </p>
      </div>

      {/* Drawer لعرض جميع الأسعار */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              📊 {isArabic ? 'جميع أسعار السوق' : 'All Market Prices'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
              {filteredItems.map((item) => (
                <div key={item.id} className="bg-white rounded-xl border border-gray-200 shadow-sm p-3">
                  <h3 className="text-sm font-bold text-gray-900 mb-1 line-clamp-1">
                    {isArabic ? item.name : item.nameEn}
                  </h3>
                  <div className="flex items-baseline gap-1 mb-1">
                    <span className="text-lg font-black text-emerald-600">
                      {formatPrice(item.price)}
                    </span>
                    <span className="text-[10px] text-gray-500">
                      {isArabic ? item.unit : item.unitEn}
                    </span>
                  </div>
                  <div className="flex items-center gap-1">
                    {getChangeIcon(item.change)}
                    {item.changePercent && (
                      <span className={`text-xs font-bold ${getChangeColor(item.change)}`}>
                        {item.change === 'up' ? '+' : '-'}{item.changePercent}%
                      </span>
                    )}
                    {item.change === 'stable' && (
                      <span className="text-xs text-gray-400">
                        {isArabic ? 'ثابت' : 'Stable'}
                      </span>
                    )}
                  </div>
                  <div className="mt-2">
                    <span className="text-[10px] px-2 py-0.5 bg-emerald-100 text-emerald-700 rounded-full font-bold">
                      {isArabic
                        ? categoryFilters.find(f => f.id === item.category)?.name
                        : categoryFilters.find(f => f.id === item.category)?.nameEn}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
