'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Bus, MapPin, Clock, Users, AlertCircle, CheckCircle, Route, ChevronLeft, ChevronRight, Heart, Car, Bike, Train, Star, Grid3X3 } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface RouteData {
  id: string;
  number: string;
  name: string;
  nameEn: string;
  from: string;
  fromEn: string;
  to: string;
  toEn: string;
  stops: number;
  nextBus: string;
  status: string;
  type: 'bus' | 'taxi' | 'private' | 'microbus';
  price?: string;
  featured?: boolean;
  available?: boolean;
}

interface TaxiStation {
  id: string;
  name: string;
  nameEn: string;
  cars: number;
}

const qudsayaCenterRoutes: RouteData[] = [
  { id: '1', number: 'Q1', name: 'خط قدسيا - دمشق', nameEn: 'Qudsaya - Damascus Line', from: 'قدسيا', fromEn: 'Qudsaya', to: 'ساحة العباسيين', toEn: 'Abbasid Square', stops: 10, nextBus: '5 دقائق', status: 'active', type: 'bus', price: '500', featured: true, available: true },
  { id: '2', number: 'Q2', name: 'خط قدسيا - المزة', nameEn: 'Qudsaya - Mezzeh Line', from: 'قدسيا', fromEn: 'Qudsaya', to: 'المزة', toEn: 'Mezzeh', stops: 8, nextBus: '8 دقائق', status: 'active', type: 'bus', price: '400', available: true },
  { id: '3', number: 'Q3', name: 'خط قدسيا - البرامكة', nameEn: 'Qudsaya - Barameka Line', from: 'قدسيا', fromEn: 'Qudsaya', to: 'البرامكة', toEn: 'Barameka', stops: 12, nextBus: '15 دقيقة', status: 'delayed', type: 'bus', price: '600', available: true },
  { id: '4', number: 'T1', name: 'تاكسي جماعي - دمشق', nameEn: 'Shared Taxi - Damascus', from: 'قدسيا', fromEn: 'Qudsaya', to: 'ساحة العباسيين', toEn: 'Abbasid Square', stops: 3, nextBus: '2 دقائق', status: 'active', type: 'taxi', price: '2000', featured: true, available: true },
  { id: '5', number: 'M1', name: 'ميكروباص - الضاحية', nameEn: 'Microbus - Dahia', from: 'قدسيا المركز', fromEn: 'Qudsaya Center', to: 'الضاحية', toEn: 'Dahia', stops: 6, nextBus: '10 دقائق', status: 'active', type: 'microbus', price: '800', available: true },
  { id: '6', number: 'P1', name: 'نقل خاص - المطار', nameEn: 'Private Transfer - Airport', from: 'قدسيا', fromEn: 'Qudsaya', to: 'مطار دمشق الدولي', toEn: 'Damascus Intl Airport', stops: 0, nextBus: 'حسب الطلب', status: 'active', type: 'private', price: '15000', featured: true, available: true }
];

const qudsayaDahiaRoutes: RouteData[] = [
  { id: '1', number: 'D1', name: 'خط الضاحية - دمشق', nameEn: 'Dahia - Damascus Line', from: 'الضاحية', fromEn: 'Dahia', to: 'ساحة العباسيين', toEn: 'Abbasid Square', stops: 15, nextBus: '7 دقائق', status: 'active', type: 'bus', price: '600', featured: true, available: true },
  { id: '2', number: 'D2', name: 'خط الضاحية - قدسيا', nameEn: 'Dahia - Qudsaya Line', from: 'الضاحية', fromEn: 'Dahia', to: 'قدسيا', toEn: 'Qudsaya', stops: 5, nextBus: '3 دقائق', status: 'active', type: 'bus', price: '300', available: true },
  { id: '3', number: 'D3', name: 'خط الضاحية - المزة', nameEn: 'Dahia - Mezzeh Line', from: 'الضاحية', fromEn: 'Dahia', to: 'المزة', toEn: 'Mezzeh', stops: 10, nextBus: '12 دقيقة', status: 'active', type: 'bus', price: '500', available: true },
  { id: '4', number: 'T2', name: 'تاكسي جماعي - دمشق', nameEn: 'Shared Taxi - Damascus', from: 'الضاحية', fromEn: 'Dahia', to: 'ساحة العباسيين', toEn: 'Abbasid Square', stops: 4, nextBus: '3 دقائق', status: 'active', type: 'taxi', price: '2500', featured: true, available: true },
  { id: '5', number: 'M2', name: 'ميكروباص - قدسيا', nameEn: 'Microbus - Qudsaya', from: 'الضاحية المركز', fromEn: 'Dahia Center', to: 'قدسيا', toEn: 'Qudsaya', stops: 8, nextBus: '5 دقائق', status: 'active', type: 'microbus', price: '700', available: true },
  { id: '6', number: 'P2', name: 'نقل خاص - المطار', nameEn: 'Private Transfer - Airport', from: 'الضاحية', fromEn: 'Dahia', to: 'مطار دمشق الدولي', toEn: 'Damascus Intl Airport', stops: 0, nextBus: 'حسب الطلب', status: 'active', type: 'private', price: '18000', featured: true, available: true }
];

const qudsayaCenterTaxi: TaxiStation[] = [
  { id: '1', name: 'محطة قدسيا المركزية', nameEn: 'Qudsaya Central Station', cars: 10 },
  { id: '2', name: 'محطة الساحة', nameEn: 'Square Station', cars: 6 },
  { id: '3', name: 'محطة المدخل', nameEn: 'Entrance Station', cars: 8 }
];

const qudsayaDahiaTaxi: TaxiStation[] = [
  { id: '1', name: 'محطة الضاحية المركزية', nameEn: 'Dahia Central Station', cars: 12 },
  { id: '2', name: 'محطة الحي الشمالي', nameEn: 'North District Station', cars: 5 },
  { id: '3', name: 'محطة السوق', nameEn: 'Market Station', cars: 7 }
];

const dataByRegion = {
  'qudsaya-center': { routes: qudsayaCenterRoutes, taxi: qudsayaCenterTaxi },
  'qudsaya-dahia': { routes: qudsayaDahiaRoutes, taxi: qudsayaDahiaTaxi }
};

const transportFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Bus },
  { id: 'bus', name: 'باصات', nameEn: 'Buses', icon: Bus },
  { id: 'microbus', name: 'ميكروباص', nameEn: 'Microbus', icon: Train },
  { id: 'taxi', name: 'تاكسي', nameEn: 'Taxi', icon: Car },
  { id: 'private', name: 'نقل خاص', nameEn: 'Private', icon: Bike },
];

export default function Transport() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const regionData = dataByRegion[region];
  const routes = regionData?.routes || [];
  const taxiStations = regionData?.taxi || [];

  const [activeFilter, setActiveFilter] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredRoutes = routes.filter(route => activeFilter === 'all' || route.type === activeFilter);

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
  }, [filteredRoutes]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  const getStatusConfig = (status: string) => {
    switch (status) {
      case 'active':
        return { icon: CheckCircle, color: 'text-emerald-600', bg: 'bg-emerald-100', text: isArabic ? 'نشط' : 'Active' };
      case 'delayed':
        return { icon: AlertCircle, color: 'text-amber-600', bg: 'bg-amber-100', text: isArabic ? 'متأخر' : 'Delayed' };
      default:
        return { icon: AlertCircle, color: 'text-red-600', bg: 'bg-red-100', text: isArabic ? 'متوقف' : 'Stopped' };
    }
  };

  const getTypeConfig = (type: string) => {
    switch (type) {
      case 'bus':
        return { icon: Bus, color: 'text-teal-600', bg: 'bg-teal-100', text: isArabic ? 'باص' : 'Bus' };
      case 'taxi':
        return { icon: Car, color: 'text-amber-600', bg: 'bg-amber-100', text: isArabic ? 'تاكسي' : 'Taxi' };
      case 'microbus':
        return { icon: Train, color: 'text-purple-600', bg: 'bg-purple-100', text: isArabic ? 'ميكروباص' : 'Microbus' };
      case 'private':
        return { icon: Bike, color: 'text-indigo-600', bg: 'bg-indigo-100', text: isArabic ? 'نقل خاص' : 'Private' };
      default:
        return { icon: Bus, color: 'text-gray-600', bg: 'bg-gray-100', text: type };
    }
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🚌 مواصلات' : '🚌 Transport'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {transportFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeFilter === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveFilter(filter.id)}
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
            {filteredRoutes.slice(0, 8).map((route) => {
              const statusConfig = getStatusConfig(route.status);
              const typeConfig = getTypeConfig(route.type);
              const TypeIcon = typeConfig.icon;
              const isFavorite = favorites.includes(route.id);

              return (
                <div key={route.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2 bg-gradient-to-br from-teal-100 to-cyan-100">
                    <div className="absolute inset-0 flex items-center justify-center">
                      <div className={`w-20 h-20 ${typeConfig.bg} rounded-2xl flex items-center justify-center shadow-lg group-hover:scale-110 transition-transform`}>
                        <TypeIcon className={`w-10 h-10 ${typeConfig.color}`} />
                      </div>
                    </div>
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(route.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-teal-600/90 text-white">
                      {route.number}
                    </span>
                    {route.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 text-[10px] font-bold rounded-full bg-amber-500 text-white flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-white" />
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    <span className={`absolute bottom-2 right-2 px-2 py-0.5 text-[9px] font-semibold rounded-full ${statusConfig.bg} ${statusConfig.color}`}>
                      {statusConfig.text}
                    </span>
                  </div>
                  <div>
                    <span className={`text-[10px] font-bold ${typeConfig.color} ${typeConfig.bg} px-2 py-0.5 rounded-full`}>
                      {typeConfig.text}
                    </span>
                    <h3 className="text-sm font-medium text-gray-900 mt-1 mb-0.5 line-clamp-1">{isArabic ? route.name : route.nameEn}</h3>
                    <div className="flex items-center gap-2 text-xs text-gray-600 mb-1">
                      <MapPin className="w-3 h-3 text-teal-500" />
                      <span className="truncate">{isArabic ? route.from : route.fromEn}</span>
                      <span className="text-gray-300">→</span>
                      <span className="truncate">{isArabic ? route.to : route.toEn}</span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="flex items-center gap-1">
                        <Route className="w-3 h-3" />
                        {route.stops} {isArabic ? 'محطة' : 'stops'}
                      </span>
                      <span className="flex items-center gap-1 text-teal-600 font-medium">
                        <Clock className="w-3 h-3" />
                        {route.nextBus}
                      </span>
                    </div>
                    <div className="mt-1 text-xs font-bold text-gray-900">
                      {route.price} {isArabic ? 'ل.س' : 'SYP'}
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredRoutes.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors bg-gradient-to-br from-teal-50 to-cyan-50 flex items-center justify-center">
                  <div className="text-center">
                    <Grid3X3 className="w-12 h-12 text-gray-400 mx-auto mb-2" />
                    <span className="text-lg font-bold text-gray-700">+{filteredRoutes.length - 8}</span>
                    <p className="text-sm text-gray-500">{isArabic ? 'عرض الكل' : 'View All'}</p>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الخطوط' : 'Browse all routes'}</p>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Taxi Stations */}
        <div className="mt-4">
          <h3 className="text-sm font-bold text-gray-900 mb-3 flex items-center gap-2">
            <Users className="w-4 h-4 text-amber-600" />
            {isArabic ? 'محطات التاكسي الجماعي' : 'Shared Taxi Stations'}
          </h3>
          <div className="grid grid-cols-3 gap-3">
            {taxiStations.map((station) => (
              <div key={station.id} className="p-3 bg-gradient-to-br from-amber-50 to-orange-50 rounded-xl border border-amber-200 hover:shadow-md transition-all cursor-pointer group">
                <div className="flex items-center justify-between">
                  <div>
                    <h4 className="font-bold text-gray-900 text-sm line-clamp-1">{isArabic ? station.name : station.nameEn}</h4>
                    <p className="text-xs text-gray-500">{station.cars} {isArabic ? 'سيارة متاحة' : 'cars available'}</p>
                  </div>
                  <div className="w-10 h-10 bg-gradient-to-br from-amber-500 to-orange-500 rounded-full flex items-center justify-center shadow-md group-hover:scale-110 transition-transform">
                    <span className="text-white font-bold text-sm">{station.cars}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الخطوط */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🚌 {isArabic ? 'جميع خطوط المواصلات' : 'All Transport Routes'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredRoutes.map((route) => {
                const typeConfig = getTypeConfig(route.type);
                const TypeIcon = typeConfig.icon;
                const isFavorite = favorites.includes(route.id);
                return (
                  <div key={route.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2 bg-gradient-to-br from-teal-100 to-cyan-100">
                      <div className="absolute inset-0 flex items-center justify-center">
                        <div className={`w-16 h-16 ${typeConfig.bg} rounded-2xl flex items-center justify-center shadow-lg`}>
                          <TypeIcon className={`w-8 h-8 ${typeConfig.color}`} />
                        </div>
                      </div>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-teal-600/90 text-white">
                        {route.number}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? route.name : route.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">{isArabic ? route.from : route.fromEn} → {isArabic ? route.to : route.toEn}</p>
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
