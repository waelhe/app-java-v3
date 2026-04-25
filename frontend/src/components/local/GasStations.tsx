'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Fuel, Star, Heart, ChevronLeft, ChevronRight, Car, Droplets, Wrench, Phone, Grid3X3 } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface GasStation {
  id: string;
  name: string;
  nameEn: string;
  type: 'gas' | 'wash' | 'service';
  address: string;
  addressEn: string;
  phone: string;
  rating: number;
  image: string;
  isOpen: boolean;
  hasWash?: boolean;
  hasOil?: boolean;
  hasAir?: boolean;
  is24h?: boolean;
}

const qudsayaDahiaStations: GasStation[] = [
  {
    id: 'd1',
    name: 'محطة بنزين الضاحية',
    nameEn: 'Dahia Gas Station',
    type: 'gas',
    address: 'الساحة الرئيسية - ضاحية قدسيا',
    addressEn: 'Main Square - Qudsaya Dahia',
    phone: '0999111222',
    rating: 4.5,
    image: 'https://images.unsplash.com/photo-1605101100278-5d1deb2b6498?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    is24h: true,
    hasWash: true,
    hasOil: true,
    hasAir: true
  },
  {
    id: 'd2',
    name: 'غسيل السيارات النظيف',
    nameEn: 'Clean Car Wash',
    type: 'wash',
    address: 'شارع الرئيسي - ضاحية قدسيا',
    addressEn: 'Main Street - Qudsaya Dahia',
    phone: '0999222333',
    rating: 4.7,
    image: 'https://images.unsplash.com/photo-1520340356584-f9917d1eea6f?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWash: true
  },
  {
    id: 'd3',
    name: 'مركز صيانة الضاحية',
    nameEn: 'Dahia Service Center',
    type: 'service',
    address: 'الحي الجنوبي - ضاحية قدسيا',
    addressEn: 'South District - Qudsaya Dahia',
    phone: '0999333444',
    rating: 4.4,
    image: 'https://images.unsplash.com/photo-1486262715619-67b85e0b08d3?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasOil: true,
    hasAir: true
  }
];

const qudsayaCenterStations: GasStation[] = [
  {
    id: 'c1',
    name: 'محطة بنزين القدس',
    nameEn: 'Al-Quds Gas Station',
    type: 'gas',
    address: 'طريق قدسيا الرئيسي',
    addressEn: 'Qudsaya Main Road',
    phone: '0999444555',
    rating: 4.6,
    image: 'https://images.unsplash.com/photo-1605101100278-5d1deb2b6498?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    is24h: true,
    hasWash: true,
    hasOil: true,
    hasAir: true
  },
  {
    id: 'c2',
    name: 'غسيل الأمل',
    nameEn: 'Al-Amal Car Wash',
    type: 'wash',
    address: 'ساحة قدسيا المركز',
    addressEn: 'Qudsaya Center Square',
    phone: '0999555666',
    rating: 4.8,
    image: 'https://images.unsplash.com/photo-1520340356584-f9917d1eea6f?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasWash: true
  },
  {
    id: 'c3',
    name: 'ورشة الصفاء',
    nameEn: 'Al-Safa Workshop',
    type: 'service',
    address: 'الحي الغربي - قدسيا',
    addressEn: 'West District - Qudsaya',
    phone: '0999666777',
    rating: 4.3,
    image: 'https://images.unsplash.com/photo-1486262715619-67b85e0b08d3?auto=format&fit=crop&w=400&q=80',
    isOpen: true,
    hasOil: true,
    hasAir: true
  }
];

const stationsByRegion: Record<Region, GasStation[]> = {
  'qudsaya-center': qudsayaCenterStations,
  'qudsaya-dahia': qudsayaDahiaStations
};

const typeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Fuel },
  { id: 'gas', name: 'محطات بنزين', nameEn: 'Gas Stations', icon: Fuel },
  { id: 'wash', name: 'غسيل سيارات', nameEn: 'Car Wash', icon: Car },
  { id: 'service', name: 'صيانة سريعة', nameEn: 'Quick Service', icon: Wrench }
];

export default function GasStations() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const stations = stationsByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredStations = stations.filter(s =>
    activeType === 'all' || s.type === activeType
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
  }, [filteredStations]);

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
          {isArabic ? '⛽ محطات وقود' : '⛽ Gas Stations'}
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
            {filteredStations.slice(0, 8).map((station) => {
              const isFavorite = favorites.includes(station.id);

              return (
                <div
                  key={station.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img
                      src={station.image}
                      alt=""
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(station.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`}
                      />
                    </button>

                    {/* Status Badge */}
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                      station.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                    }`}>
                      {station.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                    </span>

                    {/* 24h Badge */}
                    {station.is24h && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 bg-green-600/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        24H
                      </span>
                    )}

                    {/* Type Badge */}
                    <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-green-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                      {station.type === 'gas' ? (isArabic ? 'بنزين' : 'Gas') :
                       station.type === 'wash' ? (isArabic ? 'غسيل' : 'Wash') :
                       (isArabic ? 'صيانة' : 'Service')}
                    </span>

                    {/* Features Badges */}
                    <div className="absolute bottom-2 right-2 flex gap-1">
                      {station.hasWash && (
                        <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                          <Car className="w-3 h-3 text-blue-600" />
                        </span>
                      )}
                      {station.hasOil && (
                        <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                          <Droplets className="w-3 h-3 text-amber-600" />
                        </span>
                      )}
                      {station.hasAir && (
                        <span className="p-1 bg-white/90 rounded-full backdrop-blur-sm">
                          <Wrench className="w-3 h-3 text-gray-600" />
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Station Info - Airbnb Style */}
                  <div>
                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? station.name : station.nameEn}
                    </h3>

                    {/* Address */}
                    <p className="text-xs text-gray-500 mb-1 truncate">
                      📍 {isArabic ? station.address : station.addressEn}
                    </p>

                    {/* Rating & Phone */}
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{station.rating}</span>
                      </div>
                      <a
                        href={`tel:${station.phone}`}
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-green-600 hover:underline"
                      >
                        📞 {station.phone}
                      </a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredStations.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredStations.slice(0, 4).map((s, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={s.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredStations.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع المحطات' : 'Browse all stations'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع المحطات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              ⛽ {isArabic ? 'جميع محطات الوقود' : 'All Gas Stations'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredStations.map((station) => {
                const isFavorite = favorites.includes(station.id);
                return (
                  <div key={station.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={station.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(station.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-green-500/90 text-white">
                        {station.type === 'gas' ? (isArabic ? 'بنزين' : 'Gas') : station.type === 'wash' ? (isArabic ? 'غسيل' : 'Wash') : (isArabic ? 'صيانة' : 'Service')}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? station.name : station.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {isArabic ? station.address : station.addressEn}</p>
                  </div>
                );
              })}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
