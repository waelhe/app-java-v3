'use client';

import React, { useState, useRef, useEffect } from 'react';
import { ShoppingCart, Star, Heart, ChevronLeft, ChevronRight, Carrot, Beef, Fish, Shirt, Milk, Clock, ArrowLeft } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Market {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'vegetables' | 'meat' | 'fish' | 'clothes' | 'dairy';
  image: string;
  location: string;
  hours: string;
  rating: number;
  featured?: boolean;
  new?: boolean;
}

const qudsayaDahiaMarkets: Market[] = [
  {
    id: '1',
    name: 'سوق الخضار - الضاحية',
    nameEn: 'Vegetable Market - Dahia',
    type: 'خضار وفواكه',
    typeEn: 'Vegetables & Fruits',
    category: 'vegetables',
    image: 'https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - الساحة',
    hours: '5:00 - 14:00',
    rating: 4.5,
    featured: true
  },
  {
    id: '2',
    name: 'سوق اللحوم',
    nameEn: 'Meat Market',
    type: 'لحوم',
    typeEn: 'Meat',
    category: 'meat',
    image: 'https://images.unsplash.com/photo-1607623814075-e51df1bdc82f?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - الشمال',
    hours: '6:00 - 16:00',
    rating: 4.7
  },
  {
    id: '3',
    name: 'سوق الأسماك',
    nameEn: 'Fish Market',
    type: 'أسماك',
    typeEn: 'Fish',
    category: 'fish',
    image: 'https://images.unsplash.com/photo-1534604973900-c43ab4c2e0ab?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - الجنوب',
    hours: '6:00 - 14:00',
    rating: 4.4,
    new: true
  },
  {
    id: '4',
    name: 'سوق الملابس',
    nameEn: 'Clothes Market',
    type: 'ملابس',
    typeEn: 'Clothes',
    category: 'clothes',
    image: 'https://images.unsplash.com/photo-1605518216938-7c31b7b14ad0?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - الساحة',
    hours: '8:00 - 20:00',
    rating: 4.3
  },
  {
    id: '5',
    name: 'سوق الألبان الطازجة',
    nameEn: 'Fresh Dairy Market',
    type: 'ألبان',
    typeEn: 'Dairy',
    category: 'dairy',
    image: 'https://images.unsplash.com/photo-1628088062854-d1870b4553da?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - المدخل',
    hours: '6:00 - 12:00',
    rating: 4.6,
    featured: true
  },
  {
    id: '6',
    name: 'سوق الخضار الشعبي',
    nameEn: 'Popular Vegetable Market',
    type: 'خضار وفواكه',
    typeEn: 'Vegetables & Fruits',
    category: 'vegetables',
    image: 'https://images.unsplash.com/photo-1488459716781-31db52582fe9?auto=format&fit=crop&w=400&q=80',
    location: 'الضاحية - الشرق',
    hours: '5:00 - 15:00',
    rating: 4.4,
    new: true
  }
];

const qudsayaCenterMarkets: Market[] = [
  {
    id: 'q1',
    name: 'سوق الخضار - قدسيا',
    nameEn: 'Vegetable Market - Qudsaya',
    type: 'خضار وفواكه',
    typeEn: 'Vegetables & Fruits',
    category: 'vegetables',
    image: 'https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - الساحة الرئيسية',
    hours: '5:00 - 15:00',
    rating: 4.6,
    featured: true
  },
  {
    id: 'q2',
    name: 'سوق اللحوم',
    nameEn: 'Meat Market',
    type: 'لحوم',
    typeEn: 'Meat',
    category: 'meat',
    image: 'https://images.unsplash.com/photo-1607623814075-e51df1bdc82f?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - المدخل',
    hours: '6:00 - 17:00',
    rating: 4.8
  },
  {
    id: 'q3',
    name: 'سوق الأسماك الطازجة',
    nameEn: 'Fresh Fish Market',
    type: 'أسماك',
    typeEn: 'Fish',
    category: 'fish',
    image: 'https://images.unsplash.com/photo-1534604973900-c43ab4c2e0ab?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - الشرق',
    hours: '6:00 - 13:00',
    rating: 4.5,
    new: true
  },
  {
    id: 'q4',
    name: 'سوق الملابس الشعبي',
    nameEn: 'Popular Clothes Market',
    type: 'ملابس',
    typeEn: 'Clothes',
    category: 'clothes',
    image: 'https://images.unsplash.com/photo-1605518216938-7c31b7b14ad0?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - الساحة',
    hours: '9:00 - 21:00',
    rating: 4.4
  },
  {
    id: 'q5',
    name: 'سوق الألبان والبيض',
    nameEn: 'Dairy & Eggs Market',
    type: 'ألبان',
    typeEn: 'Dairy',
    category: 'dairy',
    image: 'https://images.unsplash.com/photo-1628088062854-d1870b4553da?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - الغرب',
    hours: '5:30 - 12:00',
    rating: 4.7,
    featured: true
  },
  {
    id: 'q6',
    name: 'سوق الفواكه الموسمية',
    nameEn: 'Seasonal Fruits Market',
    type: 'خضار وفواكه',
    typeEn: 'Vegetables & Fruits',
    category: 'vegetables',
    image: 'https://images.unsplash.com/photo-1488459716781-31db52582fe9?auto=format&fit=crop&w=400&q=80',
    location: 'قدسيا - الجنوب',
    hours: '6:00 - 14:00',
    rating: 4.5,
    new: true
  }
];

const dataByRegion: Record<Region, Market[]> = {
  'qudsaya-center': qudsayaCenterMarkets,
  'qudsaya-dahia': qudsayaDahiaMarkets
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: ShoppingCart },
  { id: 'vegetables', name: 'خضار', nameEn: 'Vegetables', icon: Carrot },
  { id: 'meat', name: 'لحوم', nameEn: 'Meat', icon: Beef },
  { id: 'fish', name: 'أسماك', nameEn: 'Fish', icon: Fish },
  { id: 'dairy', name: 'ألبان', nameEn: 'Dairy', icon: Milk },
  { id: 'clothes', name: 'ملابس', nameEn: 'Clothes', icon: Shirt },
];

export default function Markets() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const markets = dataByRegion[region];

  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredMarkets = markets.filter(market => {
    return activeCategory === 'all' || market.category === activeCategory;
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
  }, [filteredMarkets]);

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
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🛒 أسواق' : '🛒 Markets'}
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
            {filteredMarkets.slice(0, 8).map((market) => {
              const isFavorite = favorites.includes(market.id);

              return (
                <div
                  key={market.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img
                      src={market.image}
                      alt=""
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(market.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`}
                      />
                    </button>

                    {/* Type Badge */}
                    <div className="absolute top-2 left-2">
                      <span className="px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-lime-600/90 text-white">
                        {isArabic ? market.type : market.typeEn}
                      </span>
                    </div>

                    {/* Featured/New Badge */}
                    {(market.featured || market.new) && (
                      <div className="absolute bottom-2 left-2">
                        <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                          market.featured ? 'bg-amber-500/90 text-white' : 'bg-emerald-500/90 text-white'
                        }`}>
                          {market.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'جديد' : 'New')}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Market Info - Airbnb Style */}
                  <div>
                    {/* Location */}
                    <p className="text-xs text-gray-500 font-medium mb-0.5 truncate">
                      📍 {market.location}
                    </p>

                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? market.name : market.nameEn}
                    </h3>

                    {/* Hours & Rating */}
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-gray-500 flex items-center gap-1">
                        <Clock className="w-3 h-3" />
                        {market.hours}
                      </span>
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{market.rating}</span>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* View All Card - Airbnb Style */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                {/* Mini grid of images */}
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {filteredMarkets.slice(0, 4).map((m, i) => (
                    <div key={i} className="overflow-hidden">
                      <img 
                        src={m.image} 
                        alt="" 
                        className="w-full h-full object-cover"
                      />
                    </div>
                  ))}
                </div>
                {/* Overlay */}
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                {/* Content */}
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">
                    {isArabic ? 'عرض الكل' : 'Show all'}
                  </span>
                  <span className="text-xs text-white/80 mt-0.5">
                    {filteredMarkets.length} {isArabic ? 'سوق' : 'places'}
                  </span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          {/* Header */}
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع الأسواق' : 'All Markets'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredMarkets.length} {isArabic ? 'سوق متاح' : 'places available'}
            </p>
          </div>
          
          {/* Scrollable Content */}
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredMarkets.map((market) => {
                const isFavorite = favorites.includes(market.id);
                return (
                  <div key={market.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={market.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(market.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-lime-600/90 text-white">
                        {isArabic ? market.type : market.typeEn}
                      </span>
                    </div>
                    <p className="text-xs text-gray-500 truncate">📍 {market.location}</p>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? market.name : market.nameEn}</h3>
                    <div className="flex items-center justify-between mt-1">
                      <span className="text-xs text-gray-500 flex items-center gap-1"><Clock className="w-3 h-3" />{market.hours}</span>
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{market.rating}</span>
                      </div>
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
