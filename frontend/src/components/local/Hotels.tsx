'use client';

import React, { useRef, useState, useEffect } from 'react';
import { BedDouble, MapPin, Star, Phone, Wifi, Car, ChevronLeft, ChevronRight, Heart, Coffee, UtensilsCrossed } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Hotel {
  id: string;
  name: string;
  nameEn: string;
  type: 'hotel' | 'aparthotel' | 'guesthouse';
  address: string;
  addressEn: string;
  phone: string;
  rating: number;
  image: string;
  priceRange: string;
  stars: number;
  hasWifi?: boolean;
  hasParking?: boolean;
  hasBreakfast?: boolean;
  hasRestaurant?: boolean;
  featured?: boolean;
  new?: boolean;
}

const qudsayaDahiaHotels: Hotel[] = [
  { id: 'd1', name: 'فندق ضاحية قدسيا', nameEn: 'Qudsaya Dahia Hotel', type: 'hotel', address: 'الساحة الرئيسية - ضاحية قدسيا', addressEn: 'Main Square - Qudsaya Dahia', phone: '0999111222', rating: 4.6, image: 'https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=300&q=80', priceRange: '$$', stars: 4, hasWifi: true, hasParking: true, hasBreakfast: true, hasRestaurant: true, featured: true },
  { id: 'd2', name: 'شقق النخيل الفندقية', nameEn: 'Palm Aparthotel', type: 'aparthotel', address: 'شارع الرئيسي - ضاحية قدسيا', addressEn: 'Main Street - Qudsaya Dahia', phone: '0999222333', rating: 4.5, image: 'https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=300&q=80', priceRange: '$$', stars: 3, hasWifi: true, hasParking: true, new: true },
  { id: 'd3', name: 'بيت الضيافة', nameEn: 'Guest House', type: 'guesthouse', address: 'الحي الجنوبي - ضاحية قدسيا', addressEn: 'South District - Qudsaya Dahia', phone: '0999333444', rating: 4.3, image: 'https://images.unsplash.com/photo-1555854877-bab0e564b8d5?auto=format&fit=crop&w=300&q=80', priceRange: '$', stars: 2, hasWifi: true, hasBreakfast: true },
];

const qudsayaCenterHotels: Hotel[] = [
  { id: 'c1', name: 'فندق قدسيا الكبير', nameEn: 'Grand Qudsaya Hotel', type: 'hotel', address: 'طريق قدسيا الرئيسي', addressEn: 'Qudsaya Main Road', phone: '0999444555', rating: 4.8, image: 'https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=300&q=80', priceRange: '$$$', stars: 5, hasWifi: true, hasParking: true, hasBreakfast: true, hasRestaurant: true, featured: true },
  { id: 'c2', name: 'شقق الجبل الفندقية', nameEn: 'Mountain Aparthotel', type: 'aparthotel', address: 'ساحة قدسيا المركز', addressEn: 'Qudsaya Center Square', phone: '0999555666', rating: 4.4, image: 'https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=300&q=80', priceRange: '$$', stars: 3, hasWifi: true, hasParking: true, hasRestaurant: true, new: true },
  { id: 'c3', name: 'فندق الوادي', nameEn: 'Valley Hotel', type: 'hotel', address: 'نزلة قدسيا', addressEn: 'Qudsaya Slope', phone: '0999666777', rating: 4.2, image: 'https://images.unsplash.com/photo-1520250497591-112f2f40a3f4?auto=format&fit=crop&w=300&q=80', priceRange: '$$', stars: 3, hasWifi: true, hasBreakfast: true },
];

const hotelsByRegion: Record<Region, Hotel[]> = {
  'qudsaya-center': qudsayaCenterHotels,
  'qudsaya-dahia': qudsayaDahiaHotels
};

const typeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: BedDouble },
  { id: 'hotel', name: 'فنادق', nameEn: 'Hotels', icon: BedDouble },
  { id: 'aparthotel', name: 'شقق فندقية', nameEn: 'Aparthotels', icon: BedDouble },
  { id: 'guesthouse', name: 'بيوت ضيافة', nameEn: 'Guest Houses', icon: BedDouble }
];

export default function Hotels() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const hotels = hotelsByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredHotels = hotels.filter(h => activeType === 'all' || h.type === activeType);

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 10);
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
  }, [filteredHotels]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  const renderStars = (count: number) => {
    return Array.from({ length: count }, (_, i) => (
      <Star key={i} className="w-3 h-3 text-amber-400 fill-amber-400" />
    ));
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🏨 فنادق' : '🏨 Hotels'}
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
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

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
            {filteredHotels.slice(0, 8).map((hotel) => {
              const isFavorite = favorites.includes(hotel.id);
              return (
                <div key={hotel.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={hotel.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(hotel.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <div className="absolute top-2 left-2 flex gap-0.5 px-2 py-0.5 bg-white/90 rounded-full">
                      {renderStars(hotel.stars)}
                    </div>
                    <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-purple-500 text-white text-[10px] font-bold rounded-full">
                      {hotel.priceRange}
                    </span>
                    {hotel.featured && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 bg-purple-500 text-white text-[10px] font-bold rounded-full">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                  </div>
                  <div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{hotel.rating}</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? hotel.name : hotel.nameEn}</h3>
                    <div className="flex items-center gap-1 text-xs text-gray-500 mb-2">
                      <MapPin className="w-3 h-3" />
                      <span className="line-clamp-1">{isArabic ? hotel.address : hotel.addressEn}</span>
                    </div>
                    <div className="flex gap-2 mb-2">
                      {hotel.hasWifi && <span className="p-1 bg-gray-100 rounded"><Wifi className="w-3 h-3 text-blue-600" /></span>}
                      {hotel.hasParking && <span className="p-1 bg-gray-100 rounded"><Car className="w-3 h-3 text-gray-600" /></span>}
                      {hotel.hasBreakfast && <span className="p-1 bg-gray-100 rounded"><Coffee className="w-3 h-3 text-amber-600" /></span>}
                      {hotel.hasRestaurant && <span className="p-1 bg-gray-100 rounded"><UtensilsCrossed className="w-3 h-3 text-orange-600" /></span>}
                    </div>
                    <a href={`tel:${hotel.phone}`} onClick={(e) => e.stopPropagation()} className="flex items-center gap-1 text-xs text-purple-600 hover:text-purple-700">
                      <Phone className="w-3 h-3" />
                      <span>{hotel.phone}</span>
                    </a>
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
                  {filteredHotels.slice(0, 4).map((h, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={h.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredHotels.length} {isArabic ? 'فندق' : 'hotels'}</span>
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
              {isArabic ? 'جميع الفنادق' : 'All Hotels'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredHotels.length} {isArabic ? 'فندق متاح' : 'hotels available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredHotels.map((hotel) => {
                const isFavorite = favorites.includes(hotel.id);
                return (
                  <div key={hotel.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={hotel.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(hotel.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <div className="absolute top-2 left-2 flex gap-0.5 px-2 py-0.5 bg-white/90 rounded-full">
                        {renderStars(hotel.stars)}
                      </div>
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-purple-500 text-white text-[10px] font-bold rounded-full">
                        {hotel.priceRange}
                      </span>
                    </div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{hotel.rating}</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? hotel.name : hotel.nameEn}</h3>
                    <div className="flex items-center gap-1 text-xs text-gray-500">
                      <MapPin className="w-3 h-3" />
                      <span className="line-clamp-1">{isArabic ? hotel.address : hotel.addressEn}</span>
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
