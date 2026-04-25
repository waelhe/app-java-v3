'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Dumbbell, Trophy, MapPin, Users, ChevronLeft, ChevronRight, Flame, Heart, Star } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Sport {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  image: string;
  facilities: string[];
  facilitiesEn: string[];
  rating: number;
  reviews: number;
  featured?: boolean;
}

const qudsayaCenterSports: Sport[] = [
  { id: '1', name: 'نادي قدسيا الرياضي', nameEn: 'Qudsaya Sports Club', type: 'نادي رياضي', typeEn: 'Sports Club', image: 'https://images.unsplash.com/photo-1571902943202-507ec2618e8f?auto=format&fit=crop&w=300&q=80', facilities: ['كرة قدم', 'سباحة', 'تنس'], facilitiesEn: ['Football', 'Swimming', 'Tennis'], rating: 4.8, reviews: 120, featured: true },
  { id: '2', name: 'صالة اللياقة البدنية', nameEn: 'Fitness Gym', type: 'نادي لياقة', typeEn: 'Gym', image: 'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?auto=format&fit=crop&w=300&q=80', facilities: ['أجهزة حديثة', 'مدربين', 'ساونا'], facilitiesEn: ['Modern Equipment', 'Trainers', 'Sauna'], rating: 4.7, reviews: 95 },
  { id: '3', name: 'ملاعب كرة القدم', nameEn: 'Football Fields', type: 'ملاعب', typeEn: 'Fields', image: 'https://images.unsplash.com/photo-1508098682722-e99c43a406b2?auto=format&fit=crop&w=300&q=80', facilities: ['عشب صناعي', 'إضاءة ليلية'], facilitiesEn: ['Artificial Grass', 'Night Lighting'], rating: 4.6, reviews: 80 },
  { id: '4', name: 'مركز السباحة', nameEn: 'Swimming Center', type: 'سباحة', typeEn: 'Swimming', image: 'https://images.unsplash.com/photo-1519315901367-f34ff9154487?auto=format&fit=crop&w=300&q=80', facilities: ['مساحة كبيرة', 'دروس سباحة'], facilitiesEn: ['Large Pool', 'Swimming Lessons'], rating: 4.9, reviews: 110, featured: true },
  { id: '5', name: 'نادي الكاراتيه', nameEn: 'Karate Club', type: 'نادي رياضي', typeEn: 'Sports Club', image: 'https://images.unsplash.com/photo-1555597673-b21d5c935865?auto=format&fit=crop&w=300&q=80', facilities: ['مدرب محترف', 'أحزمة ملونة'], facilitiesEn: ['Professional Trainer', 'Colored Belts'], rating: 4.7, reviews: 65 },
];

const qudsayaDahiaSports: Sport[] = [
  { id: '1', name: 'نادي الضاحية الرياضي', nameEn: 'Dahia Sports Club', type: 'نادي رياضي', typeEn: 'Sports Club', image: 'https://images.unsplash.com/photo-1571902943202-507ec2618e8f?auto=format&fit=crop&w=300&q=80', facilities: ['كرة قدم', 'سباحة', 'تنس'], facilitiesEn: ['Football', 'Swimming', 'Tennis'], rating: 4.7, reviews: 100, featured: true },
  { id: '2', name: 'صالة اللياقة البدنية', nameEn: 'Fitness Gym', type: 'نادي لياقة', typeEn: 'Gym', image: 'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?auto=format&fit=crop&w=300&q=80', facilities: ['أجهزة حديثة', 'مدربين', 'ساونا'], facilitiesEn: ['Modern Equipment', 'Trainers', 'Sauna'], rating: 4.6, reviews: 85 },
  { id: '3', name: 'ملاعب كرة القدم', nameEn: 'Football Fields', type: 'ملاعب', typeEn: 'Fields', image: 'https://images.unsplash.com/photo-1508098682722-e99c43a406b2?auto=format&fit=crop&w=300&q=80', facilities: ['عشب صناعي', 'إضاءة ليلية'], facilitiesEn: ['Artificial Grass', 'Night Lighting'], rating: 4.5, reviews: 75 },
  { id: '4', name: 'مركز السباحة', nameEn: 'Swimming Center', type: 'سباحة', typeEn: 'Swimming', image: 'https://images.unsplash.com/photo-1519315901367-f34ff9154487?auto=format&fit=crop&w=300&q=80', facilities: ['مساحة كبيرة', 'دروس سباحة'], facilitiesEn: ['Large Pool', 'Swimming Lessons'], rating: 4.8, reviews: 95, featured: true },
];

const dataByRegion: Record<Region, Sport[]> = {
  'qudsaya-center': qudsayaCenterSports,
  'qudsaya-dahia': qudsayaDahiaSports
};

const typeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Trophy },
  { id: 'نادي رياضي', name: 'أندية', nameEn: 'Clubs', icon: Dumbbell },
  { id: 'نادي لياقة', name: 'لياقة', nameEn: 'Gym', icon: Flame },
  { id: 'ملاعب', name: 'ملاعب', nameEn: 'Fields', icon: Trophy },
  { id: 'سباحة', name: 'سباحة', nameEn: 'Swimming', icon: Users },
];

export default function Sports() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const sports = dataByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredSports = sports.filter(s => activeType === 'all' || s.type === activeType);

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
  }, [filteredSports]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '⚽ رياضة' : '⚽ Sports'}
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
            {filteredSports.slice(0, 8).map((sport) => {
              const isFavorite = favorites.includes(sport.id);
              return (
                <div key={sport.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={sport.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(sport.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-green-500/90 text-white">
                      {isArabic ? sport.type : sport.typeEn}
                    </span>
                    {sport.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                  </div>
                  <div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{sport.rating}</span>
                      <span className="text-xs text-gray-400">({sport.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? sport.name : sport.nameEn}</h3>
                    <div className="flex flex-wrap gap-1">
                      {(isArabic ? sport.facilities : sport.facilitiesEn).slice(0, 2).map((f, i) => (
                        <span key={i} className="text-[10px] px-2 py-0.5 bg-green-100 text-green-700 rounded-full">{f}</span>
                      ))}
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
                  {filteredSports.slice(0, 4).map((s, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={s.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredSports.length} {isArabic ? 'مكان' : 'places'}</span>
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
              {isArabic ? 'جميع الأندية الرياضية' : 'All Sports Places'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredSports.length} {isArabic ? 'مكان متاح' : 'places available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredSports.map((sport) => {
                const isFavorite = favorites.includes(sport.id);
                return (
                  <div key={sport.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={sport.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(sport.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-green-500/90 text-white">
                        {isArabic ? sport.type : sport.typeEn}
                      </span>
                    </div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{sport.rating}</span>
                      <span className="text-xs text-gray-400">({sport.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? sport.name : sport.nameEn}</h3>
                    <div className="flex flex-wrap gap-1">
                      {(isArabic ? sport.facilities : sport.facilitiesEn).slice(0, 2).map((f, i) => (
                        <span key={i} className="text-[10px] px-2 py-0.5 bg-green-100 text-green-700 rounded-full">{f}</span>
                      ))}
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
