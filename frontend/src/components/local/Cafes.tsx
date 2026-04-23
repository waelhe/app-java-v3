'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Coffee, Star, Heart, ChevronLeft, ChevronRight, Cigarette, Wifi } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Cafe {
  id: string;
  name: string;
  nameEn: string;
  type: 'cafe' | 'shisha' | 'dessert';
  address: string;
  addressEn: string;
  rating: number;
  image: string;
  isOpen: boolean;
  hasWifi?: boolean;
  hasShisha?: boolean;
  featured?: boolean;
  new?: boolean;
}

const qudsayaDahiaCafes: Cafe[] = [
  {
    id: 'd1',
    name: 'كافيه النخيل',
    nameEn: 'Palm Cafe',
    type: 'cafe',
    address: 'الساحة الرئيسية - ضاحية قدسيا',
    addressEn: 'Main Square - Qudsaya Dahia',
    rating: 4.7,
    image: 'https://images.unsplash.com/photo-1554118811-1e0d58224f24?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWifi: true,
    hasShisha: true,
    featured: true
  },
  {
    id: 'd2',
    name: 'كافيه الروضة',
    nameEn: 'Rawda Cafe',
    type: 'shisha',
    address: 'شارع الرئيسي - ضاحية قدسيا',
    addressEn: 'Main Street - Qudsaya Dahia',
    rating: 4.5,
    image: 'https://images.unsplash.com/photo-1445116572660-236099ec97a0?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasShisha: true,
    new: true
  },
  {
    id: 'd3',
    name: 'حلويات الضاحية',
    nameEn: 'Dahia Sweets',
    type: 'dessert',
    address: 'الحي الجنوبي - ضاحية قدسيا',
    addressEn: 'South District - Qudsaya Dahia',
    rating: 4.8,
    image: 'https://images.unsplash.com/photo-1551024506-0bccd828d307?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWifi: true,
    featured: true
  },
  {
    id: 'd4',
    name: 'كافيه الجسر',
    nameEn: 'Bridge Cafe',
    type: 'cafe',
    address: 'نزلة الضاحية',
    addressEn: 'Dahia Slope',
    rating: 4.4,
    image: 'https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWifi: true,
    hasShisha: true
  }
];

const qudsayaCenterCafes: Cafe[] = [
  {
    id: 'c1',
    name: 'كافيه الجبل',
    nameEn: 'Mountain Cafe',
    type: 'cafe',
    address: 'ساحة قدسيا المركز',
    addressEn: 'Qudsaya Center Square',
    rating: 4.6,
    image: 'https://images.unsplash.com/photo-1554118811-1e0d58224f24?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWifi: true,
    hasShisha: true,
    featured: true
  },
  {
    id: 'c2',
    name: 'استراحة الصفاء',
    nameEn: 'Al-Safa Lounge',
    type: 'shisha',
    address: 'شارع المدرسة - قدسيا',
    addressEn: 'School Street - Qudsaya',
    rating: 4.5,
    image: 'https://images.unsplash.com/photo-1445116572660-236099ec97a0?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasShisha: true,
    new: true
  },
  {
    id: 'c3',
    name: 'حلويات القدس',
    nameEn: 'Al-Quds Sweets',
    type: 'dessert',
    address: 'الحي الغربي - قدسيا',
    addressEn: 'West District - Qudsaya',
    rating: 4.9,
    image: 'https://images.unsplash.com/photo-1551024506-0bccd828d307?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWifi: true,
    featured: true
  },
  {
    id: 'c4',
    name: 'كافيه الوادي',
    nameEn: 'Valley Cafe',
    type: 'cafe',
    address: 'نزلة قدسيا',
    addressEn: 'Qudsaya Slope',
    rating: 4.3,
    image: 'https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWifi: true
  }
];

const cafesByRegion: Record<Region, Cafe[]> = {
  'qudsaya-center': qudsayaCenterCafes,
  'qudsaya-dahia': qudsayaDahiaCafes
};

const typeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Coffee },
  { id: 'cafe', name: 'مقاهي', nameEn: 'Cafes', icon: Coffee },
  { id: 'shisha', name: 'أراكيل', nameEn: 'Shisha', icon: Cigarette },
  { id: 'dessert', name: 'حلويات', nameEn: 'Desserts', icon: Star }
];

export default function Cafes() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const cafes = cafesByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredCafes = cafes.filter(c =>
    activeType === 'all' || c.type === activeType
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
  }, [filteredCafes]);

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
          {isArabic ? '☕ مقاهي وحلويات' : '☕ Cafes & Desserts'}
        </h2>

        {/* Type Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {typeFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeType === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveType(filter.id)}
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
            {filteredCafes.slice(0, 8).map((cafe) => {
              const isFavorite = favorites.includes(cafe.id);

              return (
                <div
                  key={cafe.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img
                      src={cafe.image}
                      alt=""
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(cafe.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`}
                      />
                    </button>

                    {/* Status Badge */}
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                      cafe.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                    }`}>
                      {cafe.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                    </span>

                    {/* Type Badge */}
                    <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                      {cafe.type === 'cafe' ? (isArabic ? 'مقهى' : 'Cafe') :
                       cafe.type === 'shisha' ? (isArabic ? 'أركيلة' : 'Shisha') :
                       (isArabic ? 'حلويات' : 'Desserts')}
                    </span>

                    {/* Features Badges */}
                    <div className="absolute bottom-2 right-2 flex gap-1">
                      {cafe.hasWifi && (
                        <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                          <Wifi className="w-3 h-3 text-blue-600" />
                        </span>
                      )}
                      {cafe.hasShisha && (
                        <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                          <Cigarette className="w-3 h-3 text-gray-600" />
                        </span>
                      )}
                    </div>

                    {/* Featured/New Badge */}
                    {(cafe.featured || cafe.new) && (
                      <div className="absolute top-2 right-10">
                        <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                          cafe.featured ? 'bg-amber-500/90 text-white' : 'bg-emerald-500/90 text-white'
                        }`}>
                          {cafe.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'جديد' : 'New')}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Cafe Info - Airbnb Style */}
                  <div>
                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? cafe.name : cafe.nameEn}
                    </h3>

                    {/* Address */}
                    <p className="text-xs text-gray-500 mb-1 truncate">
                      {isArabic ? cafe.address : cafe.addressEn}
                    </p>

                    {/* Rating */}
                    <div className="flex items-center gap-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{cafe.rating}</span>
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
                  {filteredCafes.slice(0, 4).map((c, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={c.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredCafes.length} {isArabic ? 'مكان' : 'places'}</span>
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
              {isArabic ? 'جميع المقاهي والحلويات' : 'All Cafes & Desserts'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredCafes.length} {isArabic ? 'مكان متاح' : 'places available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredCafes.map((cafe) => {
                const isFavorite = favorites.includes(cafe.id);
                return (
                  <div key={cafe.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={cafe.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(cafe.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${cafe.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'}`}>
                        {cafe.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                      </span>
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {cafe.type === 'cafe' ? (isArabic ? 'مقهى' : 'Cafe') : cafe.type === 'shisha' ? (isArabic ? 'أركيلة' : 'Shisha') : (isArabic ? 'حلويات' : 'Desserts')}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? cafe.name : cafe.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">{isArabic ? cafe.address : cafe.addressEn}</p>
                    <div className="flex items-center gap-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{cafe.rating}</span>
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
